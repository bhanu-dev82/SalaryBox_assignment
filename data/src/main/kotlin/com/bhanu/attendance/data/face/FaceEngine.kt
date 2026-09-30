package com.bhanu.attendance.data.face

import android.graphics.Bitmap
import com.bhanu.attendance.domain.face.FaceObservation
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import kotlinx.coroutines.flow.Flow

/** One analysed frame's result. */
sealed interface FaceFrameResult {
    /** A face was found and is usable. [faceCount] is >1 when extra faces were in frame. */
    data class Detected(val observation: FaceObservation, val faceCount: Int) : FaceFrameResult

    /** No face in this frame. */
    data object NoFace : FaceFrameResult
}

/**
 * Face landmarking, abstracted away from MediaPipe.
 *
 * The interface exists so that `:domain` and the feature modules never see a MediaPipe type,
 * so tests can substitute a synthetic engine, and so the engine is swappable — the decision
 * between MediaPipe and ML Kit is documented in `docs/DECISIONS.md` and is reversible.
 *
 * Contract for every method: **never throw**. A missing model, an unsupported pixel format and
 * a failed native call all surface as [lastError] or a failed [initialise], because an
 * exception escaping into a camera analyser thread would take the preview down with it.
 */
interface FaceEngine : AutoCloseable {

    /**
     * Emissions from [submit], newest-wins under back-pressure.
     *
     * Collected from a coroutine; note that MediaPipe delivers results on its own worker
     * thread, so a collector must not assume it is on the main thread.
     */
    val observations: Flow<FaceFrameResult>

    /** Loads the model. Idempotent; returns [Outcome.Failure] rather than throwing. */
    suspend fun initialise(): Outcome<Unit>

    /** Queues a frame for analysis. Returns immediately; the result arrives via [observations]. */
    suspend fun submit(bitmap: Bitmap, meanLuma: Float)

    /** Downscales so inference never runs on a full-resolution camera frame. */
    fun decimate(bitmap: Bitmap, maxDimension: Int): Bitmap

    /** Mean Rec. 601 luma in 0..255, for the lighting quality gate. */
    fun meanLuma(bitmap: Bitmap): Float

    /** The most recent engine-level failure, if any. */
    fun lastError(): AppError?
}
