package com.bhanu.attendance.data.face

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.bhanu.attendance.core.common.logging.AppLogger
import com.bhanu.attendance.domain.face.FaceLandmark
import com.bhanu.attendance.domain.face.FaceObservation
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * MediaPipe face landmarking, exposed through a domain-typed interface.
 *
 * ### Why MediaPipe `FaceLandmarker` and not ML Kit face detection
 *
 * The assignment's app must run on a device, offline, with no account. ML Kit's face API
 * requires Google Play services, which means the app silently does not work on devices
 * without them, and cannot be reasoned about offline. MediaPipe's `FaceLandmarker` runs
 * entirely on-device from a model bundled in `assets/`, and emits 478 landmarks rather than
 * ML Kit's contour set — a materially better basis for a geometric descriptor.
 *
 * ### Why `detectAsync` and not `detectForVideo`
 *
 * `detectForVideo` throws `MediaPipeException` (FAILED_PRECONDITION) unless the task was
 * created in `VIDEO` mode. For a live camera the correct call is `detectAsync` in
 * `LIVE_STREAM` mode with a result listener, which is non-blocking and drops frames under
 * back-pressure by design. Camera frames arrive faster than inference completes, so a
 * blocking call in the analyser would back the whole camera pipeline up.
 *
 * ### Threading
 *
 * The graph is not thread-safe. The GPU delegate must be created *and* used on the thread
 * that initialised it, so the CPU delegate is used by default and all calls are funnelled
 * through a single-threaded dispatcher.
 */
@Singleton
class MediaPipeFaceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger,
) : FaceEngine, AutoCloseable {

    // A HandlerThread, not a pool thread: FaceLandmarker installs a Handler during
    // createFromOptions, which throws if the thread has no Looper. Creation and
    // detectAsync both run here so the task is never touched from two threads.
    private val inferenceThread = HandlerThread("FaceInference").apply { start() }
    private val inferenceDispatcher = Handler(inferenceThread.looper).asCoroutineDispatcher()

    @Volatile
    private var landmarker: FaceLandmarker? = null

    @Volatile
    private var lastTimestampMillis: Long = 0L

    /** One frame in flight. A second detectAsync while the first is running is dropped. */
    private val inFlight = AtomicBoolean(false)

    /**
     * Facts for the frame currently inside `detectAsync`. The result listener does not
     * receive the bitmap, so width, height and luma travel with the frame here.
     */
    private val pending = AtomicReference<FrameFacts?>(null)

    private val _observations = MutableSharedFlow<FaceFrameResult>(
        replay = 0,
        extraBufferCapacity = 1,
        // DROP_OLDEST: under back-pressure the newest frame is the useful one. Blocking the
        // camera analyser instead would stall the preview.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val observations: Flow<FaceFrameResult> = _observations.asSharedFlow()

    @Volatile
    private var lastError: AppError? = null

    override suspend fun initialise(): Outcome<Unit> = withContext(inferenceDispatcher) {
        if (landmarker != null) return@withContext Outcome.success(Unit)
        runCatching {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET_NAME)
                        .setDelegate(Delegate.CPU)
                        .build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(MIN_CONFIDENCE)
                .setMinFacePresenceConfidence(MIN_CONFIDENCE)
                .setMinTrackingConfidence(MIN_CONFIDENCE)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .setResultListener { result, _ ->
                    // Invoked on a MediaPipe-owned worker thread; must not touch UI state
                    // directly. `_observations` is a thread-safe SharedFlow, so emitting here
                    // is correct and collectors are expected to marshal onward.
                    val facts = pending.getAndSet(null)
                    val faces = result.faceLandmarks()
                    when {
                        facts == null -> Unit
                        faces.isEmpty() -> _observations.tryEmit(FaceFrameResult.NoFace)
                        else -> _observations.tryEmit(
                            FaceFrameResult.Detected(
                                toObservation(faces[0], facts),
                                faces.size,
                            )
                        )
                    }
                    facts?.done?.complete(Unit)
                }
                .setErrorListener { throwable ->
                    logger.w(TAG, "MediaPipe reported an error", throwable)
                    lastError = AppError.FaceEngineUnavailable
                    pending.getAndSet(null)?.done?.complete(Unit)
                    _observations.tryEmit(FaceFrameResult.NoFace)
                }
                .build()
            landmarker = FaceLandmarker.createFromOptions(context, options)
            logger.i(TAG, "FaceLandmarker ready")
        }.fold(
            onSuccess = { Outcome.success(Unit) },
            onFailure = { throwable ->
                // A missing or unloadable model surfaces here, at creation, not at first
                // detect. Surfaced as a typed error so the UI can explain it instead of dying.
                logger.e(TAG, "Could not initialise FaceLandmarker", throwable)
                lastError = AppError.FaceModelMissing
                Outcome.failure(AppError.FaceModelMissing)
            },
        )
    }

    override suspend fun submit(bitmap: Bitmap, meanLuma: Float) {
        // Callers are supposed to initialise first. Doing it here as well means a missed
        // call cannot silently drop every frame, which is what "face not found" looked like.
        if (landmarker == null) {
            val ready = initialise()
            if (ready is Outcome.Failure) {
                lastError = ready.error
                return
            }
        }
        val engine = landmarker ?: run {
            lastError = AppError.FaceEngineUnavailable
            return
        }
        if (!inFlight.compareAndSet(false, true)) return
        // MediaPipe only accepts ARGB_8888. Converting here rather than crashing deep inside
        // the native layer keeps the failure legible. `source` is kept reachable until the
        // result listener runs: detectAsync reads the bitmap after this function would
        // otherwise have returned.
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
        if (source == null) {
            inFlight.set(false)
            return
        }
        val copied = source !== bitmap
        val facts = FrameFacts(
            width = source.width,
            height = source.height,
            meanLuma = meanLuma,
            done = CompletableDeferred(),
        )
        pending.set(facts)
        try {
            val queued = withContext(inferenceDispatcher) {
                runCatching {
                    // Timestamps must be strictly increasing. uptimeMillis is monotonic, unlike
                    // wall-clock time, so it cannot go backwards when the system clock is adjusted.
                    var now = SystemClock.uptimeMillis()
                    if (now <= lastTimestampMillis) now = lastTimestampMillis + 1
                    lastTimestampMillis = now
                    engine.detectAsync(BitmapImageBuilder(source).build(), now)
                    true
                }.getOrElse { throwable ->
                    logger.w(TAG, "detectAsync failed", throwable)
                    lastError = AppError.FaceEngineUnavailable
                    false
                }
            }
            if (!queued) {
                pending.compareAndSet(facts, null)
                facts.done.complete(Unit)
                _observations.tryEmit(FaceFrameResult.NoFace)
                return
            }
            val finished = withTimeoutOrNull(INFERENCE_TIMEOUT_MILLIS) { facts.done.await() }
            if (finished == null) {
                logger.w(TAG, "Face inference timed out")
                pending.compareAndSet(facts, null)
                if (!facts.done.isCompleted) facts.done.complete(Unit)
                _observations.tryEmit(FaceFrameResult.NoFace)
            }
        } finally {
            if (copied && !source.isRecycled) source.recycle()
            inFlight.set(false)
        }
    }

    /** Nearest-neighbour downscale, so a 12MP frame never reaches inference at full size. */
    override fun decimate(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        return runCatching {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        }.getOrDefault(bitmap)
    }

    override fun meanLuma(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0f
        // Downscale to a tiny probe before reading pixels: the lighting gate is a sanity
        // check, not a measurement, and allocating a full-frame IntArray (~3.6 MB at 720p)
        // on every camera frame churns the GC. A 48x48 probe (2,304 px) is ample.
        val probeWidth = 48
        val probeHeight = 48
        val probe = runCatching {
            Bitmap.createScaledBitmap(bitmap, probeWidth, probeHeight, false)
        }.getOrNull() ?: return 0f
        try {
            val pixels = IntArray(probeWidth * probeHeight)
            probe.getPixels(pixels, 0, probeWidth, 0, 0, probeWidth, probeHeight)
            var total = 0L
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                total += (299L * r + 587L * g + 114L * b) / 1000L
            }
            return if (pixels.isEmpty()) 0f else (total.toFloat() / pixels.size)
        } finally {
            // createScaledBitmap can return the source when the size already matches.
            // Recycling that would hand MediaPipe a dead bitmap and look like "no face".
            if (probe !== bitmap && !probe.isRecycled) probe.recycle()
        }
    }

    override fun lastError(): AppError? = lastError

    /**
     * Releases the native graph.
     *
     * `FaceLandmarker` is `AutoCloseable` and holds native memory; leaking it across a camera
     * rebind is a slow leak, so this is called from the camera composable's `onDispose`.
     */
    override fun close() {
        runCatching { landmarker?.close() }
            .onFailure { logger.w(TAG, "Error closing FaceLandmarker", it) }
        landmarker = null
        inferenceThread.quitSafely()
    }

    /**
     * Converts MediaPipe landmarks to the domain type.
     *
     * `NormalizedLandmark` exposes `x()`, `y()`, `z()` and an `Optional<Float>` `visibility()`
     * that MediaPipe leaves empty for this task, so nothing depends on it.
     */
    private fun toObservation(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        facts: FrameFacts,
    ): FaceObservation = FaceObservation(
        landmarks = landmarks.map { FaceLandmark(it.x(), it.y(), it.z()) },
        imageWidth = facts.width,
        imageHeight = facts.height,
        // The lighting gate compares this to minMeanLuma. Leaving it at 0 rejects every
        // real face as "too dark", so the value measured on the submitted frame is required.
        meanLuma = facts.meanLuma,
    )

    private class FrameFacts(
        val width: Int,
        val height: Int,
        val meanLuma: Float,
        val done: CompletableDeferred<Unit>,
    )

    companion object {
        const val TAG = "FaceEngine"
        const val MODEL_ASSET_NAME = "face_landmarker.task"
        const val MIN_CONFIDENCE = 0.5f
        private const val INFERENCE_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Little-endian float32 conversion for storing a descriptor as a BLOB.
 *
 * Named `toLittleEndianFloats` rather than `toFloatArray` to avoid colliding with the
 * stdlib `ByteBuffer.toFloatArray()` extension.
 */
fun FloatArray.toByteBuffer(): ByteBuffer =
    ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        // asFloatBuffer() is a view. It writes the floats into this buffer but does not
        // move this buffer's position, which stays at 0 with limit already at capacity.
        // flip() would set the limit to that position (0) and the stored blob would be empty,
        // so a successful enrolment could not be matched and punch-in crashed on load.
        asFloatBuffer().put(this@toByteBuffer)
    }

fun ByteBuffer.toLittleEndianFloats(size: Int): FloatArray {
    val out = FloatArray(size)
    order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
    return out
}
