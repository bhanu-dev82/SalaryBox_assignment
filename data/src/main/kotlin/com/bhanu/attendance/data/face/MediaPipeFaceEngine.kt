package com.bhanu.attendance.data.face

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.bhanu.attendance.core.common.dispatchers.AppDispatchers
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val dispatchers: AppDispatchers,
    private val logger: AppLogger,
) : FaceEngine, AutoCloseable {

    // FaceLandmarker is single-threaded: every call is serialised through this dispatcher.
    private val inferenceDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    @Volatile
    private var landmarker: FaceLandmarker? = null

    @Volatile
    private var lastTimestampMillis: Long = 0L

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

    override suspend fun initialise(): Outcome<Unit> = withContext(dispatchers.io) {
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
                    val faces = result.faceLandmarks()
                    if (faces.isEmpty()) {
                        _observations.tryEmit(FaceFrameResult.NoFace)
                    } else {
                        _observations.tryEmit(
                            FaceFrameResult.Detected(toObservation(faces[0]), faces.size)
                        )
                    }
                }
                .setErrorListener { throwable ->
                    logger.w(TAG, "MediaPipe reported an error", throwable)
                    lastError = AppError.FaceEngineUnavailable
                }
                .build()
            landmarker = FaceLandmarker.createFromOptions(context, options)
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
        val engine = landmarker ?: run {
            lastError = AppError.FaceEngineUnavailable
            return
        }
        // MediaPipe only accepts ARGB_8888. Converting here rather than crashing deep inside
        // the native layer keeps the failure legible.
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        }
        withContext(inferenceDispatcher) {
            runCatching {
                // Timestamps must be strictly increasing. uptimeMillis is monotonic, unlike
                // wall-clock time, so it cannot go backwards when the system clock is adjusted.
                val now = SystemClock.uptimeMillis()
                if (now <= lastTimestampMillis) return@runCatching
                lastTimestampMillis = now
                engine.detectAsync(BitmapImageBuilder(source).build(), now)
            }.onFailure { throwable ->
                logger.w(TAG, "detectAsync failed", throwable)
                lastError = AppError.FaceEngineUnavailable
            }
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
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        // Rec. 601 luma. A sparse sample is enough: this is a lighting sanity check, not a
        // measurement, and reading 8 megapixels every frame would cost more than it is worth.
        var total = 0L
        var count = 0
        val step = (width * height / SAMPLE_COUNT).coerceAtLeast(1)
        var i = 0
        while (i < pixels.size) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            total += (299L * r + 587L * g + 114L * b) / 1000L
            count++
            i += step
        }
        return if (count == 0) 0f else (total.toFloat() / count)
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
    }

    /**
     * Converts MediaPipe landmarks to the domain type.
     *
     * `NormalizedLandmark` exposes `x()`, `y()`, `z()` and an `Optional<Float>` `visibility()`
     * that MediaPipe leaves empty for this task, so nothing depends on it.
     */
    private fun toObservation(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
    ): FaceObservation = FaceObservation(
        landmarks = landmarks.map { FaceLandmark(it.x(), it.y(), it.z()) },
        // MediaPipe reports landmarks normalised to the analysed frame. The image dimensions
        // are carried on the bitmap by the caller for the selfie pipeline, not by the engine,
        // so these stay at 0 here; the size gate uses the landmark bounding box, which is
        // already expressed in the same normalised units.
        imageWidth = 0,
        imageHeight = 0,
        meanLuma = 0f,
    )

    companion object {
        const val TAG = "FaceEngine"
        const val MODEL_ASSET_NAME = "face_landmarker.task"
        const val MIN_CONFIDENCE = 0.5f
        private const val SAMPLE_COUNT = 4_096
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
        asFloatBuffer().put(this@toByteBuffer)
        flip()
    }

fun ByteBuffer.toLittleEndianFloats(size: Int): FloatArray {
    val out = FloatArray(size)
    order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
    return out
}
