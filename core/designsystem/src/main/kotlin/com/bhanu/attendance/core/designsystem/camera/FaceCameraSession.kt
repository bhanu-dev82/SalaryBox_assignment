package com.bhanu.attendance.core.designsystem.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.bhanu.attendance.core.common.logging.AppLogger
import com.bhanu.attendance.data.face.FaceEngine
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Failures the camera pipeline can hit, each with a message a person can act on. */
sealed interface CameraFailure {
    data class Bind(val detail: String) : CameraFailure
    data object NoCamera : CameraFailure
    data object CaptureFailed : CameraFailure
}

/**
 * Owns one CameraX session: preview, frame analysis and still capture.
 *
 * Created once per composition and released in `onDispose`. Split out of the composable so the
 * camera lifecycle is explicit and testable, rather than spread across `addListener` callbacks
 * inside a UI function.
 *
 * ### Back-pressure
 *
 * The analyser does not close the [ImageProxy] until inference for that frame has finished.
 * `STRATEGY_KEEP_ONLY_LATEST` then drops every frame that arrived meanwhile, so a slow
 * device cannot queue bitmaps until it runs out of memory. The proxy is still closed
 * exactly once, including when decode or inference throws.
 */
class FaceCameraSession(
    private val context: Context,
    private val faceEngine: FaceEngine,
    private val logger: AppLogger,
) {
    /** Camera frames arrive here on a dedicated thread, never the main thread. */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    val previewView: PreviewView = PreviewView(context).apply {
        // COMPATIBLE uses a TextureView, which works on every device. PERFORMANCE is
        // marginally faster but is not universally safe.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // FILL_CENTER centre-crops, so the preview and the analysed frame share a centre and
        // the overlay needs only a uniform scale rather than letterbox padding.
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    /**
     * Binds preview + analysis + still capture to [lifecycleOwner].
     *
     * `onFrame` is invoked once per accepted frame. Errors are reported through [onFailure]
     * rather than thrown, because an exception here would surface as an uncaught exception on
     * a listener thread and kill the process.
     */
    suspend fun bind(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        onFrame: suspend (Bitmap) -> Unit,
        onFailure: (CameraFailure) -> Unit,
    ) {
        val cameraProvider = try {
            ProcessCameraProvider.getInstance(context).awaitCancellable()
        } catch (t: Throwable) {
            logger.w(TAG, "Camera provider unavailable", t)
            onFailure(CameraFailure.NoCamera)
            return
        }
        provider = cameraProvider

        if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            onFailure(CameraFailure.NoCamera)
            return
        }

        // Cancellation is cooperative. A confirm or a dispose can unbind while this
        // function is past the provider future; binding after that turns the camera back on.
        if (!coroutineContext.isActive) return

        // Sensor buffers are landscape. rotationDegrees is how far to turn them so they
        // match this target. Without it, MediaPipe is shown a sideways face and reports
        // that no face is present.
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            // 720p is ample for landmark detection and far cheaper than 1080p on a low-end
            // handset, where this runs continuously.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .build()

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                // The proxy stays open until inference returns. KEEP_ONLY_LATEST then
                // drops everything that arrived meanwhile, instead of queueing bitmaps.
                val upright = uprightBitmap(imageProxy) ?: return@setAnalyzer
                runBlocking { onFrame(upright) }
            } catch (t: Throwable) {
                logger.w(TAG, "Frame analysis failed", t)
            } finally {
                imageProxy.close()
            }
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setTargetRotation(rotation)
            .build()
        imageCapture = capture

        // Cancellation is cooperative. A confirm or a dispose can unbind while this
        // function is still between the provider future and bindToLifecycle; binding
        // after that would turn the camera back on.
        if (!coroutineContext.isActive) return

        runCatching {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis,
                capture,
            )
        }.onFailure { throwable ->
            logger.e(TAG, "bindToLifecycle failed", throwable)
            onFailure(
                CameraFailure.Bind(
                    throwable.message?.takeIf { it.isNotBlank() }
                        ?: "This device could not start the camera."
                )
            )
        }
    }

    /**
     * Captures a still and returns downscaled JPEG bytes, or null on failure.
     *
     * Used for the attendance selfie. The encode happens off the main thread; CameraX itself
     * writes the file on its own executor.
     */
    suspend fun captureJpeg(maxDimension: Int = 1280, quality: Int = 85): ByteArray? {
        val capture = imageCapture ?: return null
        // A relative path lands in the process working directory, which this app cannot
        // write. The still has to go in cache, then the bytes are what get stored.
        val destination = File(context.cacheDir, "capture-${System.nanoTime()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(destination).build()
        return try {
            suspendCancellableCoroutine { continuation ->
                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                            val bytes = runCatching {
                                val decoded = android.graphics.BitmapFactory.decodeFile(destination.absolutePath)
                                decoded?.let {
                                    val encoded = com.bhanu.attendance.data.storage.SelfieEncoder()
                                        .encode(it, maxDimension, quality)
                                    it.recycle()
                                    encoded
                                }
                            }.getOrNull()
                            if (continuation.isActive) continuation.resume(bytes)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            logger.w(TAG, "Still capture failed", exception)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } catch (t: Throwable) {
            logger.w(TAG, "captureJpeg threw", t)
            null
        } finally {
            runCatching { destination.delete() }
        }
    }

    /**
     * `ImageProxy.toBitmap()` copies pixels but does **not** apply [ImageProxy.getImageInfo]
     * rotation. BlazeFace is trained on upright faces, so a 90° or 270° sensor buffer comes
     * back as "no face" on a phone held in portrait.
     */
    private fun uprightBitmap(imageProxy: ImageProxy): Bitmap? {
        val raw = runCatching { imageProxy.toBitmap() }.getOrElse {
            logger.w(TAG, "Frame decode failed", it)
            return null
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        }.onSuccess { rotated ->
            if (rotated !== raw) raw.recycle()
        }.getOrElse {
            logger.w(TAG, "Frame rotate failed", it)
            raw
        }
    }

    /**
     * Stops the preview without shutting down the analyser thread, so a retake can [bind] again.
     * [release] is the one that ends the thread, and it is only safe once the screen is gone.
     */
    fun unbind() {
        runCatching { provider?.unbindAll() }
            .onFailure { logger.w(TAG, "Unbind failed", it) }
        provider = null
        imageCapture = null
    }

    fun release() {
        unbind()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "FaceCamera"

        /** Frames are decimated to at most this on the longest edge before inference. */
        const val ANALYSIS_MAX_DIMENSION = 720
    }
}

fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Awaits a [ListenableFuture] without pulling in `kotlinx-coroutines-guava`.
 *
 * Hand-rolled because it is twenty lines and the dependency is not otherwise needed; the APK
 * is already large from MediaPipe's native libraries.
 */
suspend fun <T> ListenableFuture<T>.awaitCancellable(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                runCatching { get() }
                    .onSuccess { if (continuation.isActive) continuation.resume(it) }
                    .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
            },
            MainThreadExecutor,
        )
        continuation.invokeOnCancellation { cancel(false) }
    }

/** Runs continuations on the main thread, matching CameraX's own listener expectations. */
private object MainThreadExecutor : java.util.concurrent.Executor {
    override fun execute(command: Runnable) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(command)
    }
}
