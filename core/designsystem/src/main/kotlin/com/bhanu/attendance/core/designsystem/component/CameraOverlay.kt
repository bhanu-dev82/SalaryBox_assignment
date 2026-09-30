package com.bhanu.attendance.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bhanu.attendance.domain.face.FaceObservation
import com.bhanu.attendance.domain.face.FacePoseEstimator
import com.bhanu.attendance.domain.face.QualityIssue

/**
 * Face-positioning guide drawn over the camera preview.
 *
 * The oval is a *guide*, not a hard constraint: it shows where to be, but the actual accept
 * decision is made by [com.bhanu.attendance.domain.face.FaceQualityEvaluator] on the
 * landmarks. Coupling the drawn oval to the real bounds would make the overlay jitter, and a
 * jittering overlay on a face check reads as "the app is broken".
 *
 * The status line is the accessibility-critical part: it is the only thing telling a user
 * *why* the capture is not being accepted.
 */
@Composable
fun FaceGuideOverlay(
    issue: QualityIssue,
    modifier: Modifier = Modifier,
    observation: FaceObservation? = null,
    progress: Float = 0f,
) {
    val target by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        label = "enrolment-progress",
    )
    val guideColor by animateColorAsState(
        targetValue = guideColorFor(issue),
        label = "guide-color",
    )

    Box(modifier = modifier.clearAndSetSemantics { }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.68f
            val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            val strokeWidth = 4.dp.toPx()

            // Dim everything *outside* the guide so the eye goes to the right place.
            //
            // Drawn as four rectangles rather than a full-screen fill plus a BlendMode.Clear
            // cut-out: the camera preview is a SurfaceView in its own window layer, so Clear
            // punches a hole through the Compose canvas down to the black window background
            // instead of revealing the preview. On a real device that painted the guide's
            // interior solid black, hiding the very area the person needs to see in order to
            // position their face.
            val ovalHeight = side * 1.25f
            val scrim = Color.Black.copy(alpha = 0.45f)
            drawRect(
                color = scrim,
                topLeft = Offset.Zero,
                size = Size(size.width, topLeft.y),
            )
            drawRect(
                color = scrim,
                topLeft = Offset(0f, topLeft.y + ovalHeight),
                size = Size(size.width, size.height - (topLeft.y + ovalHeight)),
            )
            drawRect(
                color = scrim,
                topLeft = Offset(0f, topLeft.y),
                size = Size(topLeft.x, ovalHeight),
            )
            drawRect(
                color = scrim,
                topLeft = Offset(topLeft.x + side, topLeft.y),
                size = Size(size.width - (topLeft.x + side), ovalHeight),
            )

            drawOval(
                color = guideColor,
                topLeft = topLeft,
                size = Size(side, side * 1.25f),
                style = Stroke(width = strokeWidth),
            )

            // Progress arc, drawn only while enrolling so it is not visual noise at
            // verification time.
            if (target > 0f) {
                drawArc(
                    color = guideColor,
                    startAngle = -90f,
                    sweepAngle = 360f * target,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(side, side * 1.25f),
                    style = Stroke(width = strokeWidth * 2.5f),
                )
            }

            // Live landmark trace, so it is visible that the mesh is actually being found.
            observation?.let { obs ->
                val points = obs.landmarks
                if (points.isNotEmpty()) {
                    // Only the oval contour, which is the readable part at a glance.
                    val contour = com.bhanu.attendance.domain.face.Landmarks.FACE_OVAL
                    val path = androidx.compose.ui.graphics.Path()
                    var started = false
                    for (index in contour) {
                        val point = points.getOrNull(index) ?: continue
                        val x = point.x * size.width
                        val y = point.y * size.height
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                    path.close()
                    drawPath(
                        path = path,
                        color = guideColor.copy(alpha = 0.55f),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** The coaching line under the preview. Empty guidance is worse than none, so it never is. */
@Composable
fun CaptureHint(
    issue: QualityIssue,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    val message = when (issue) {
        QualityIssue.OK -> "Hold still"
        QualityIssue.POSITIONING -> "Position your face in the oval"
        QualityIssue.NEED_DIFFERENT_POSE -> "Good — turn your head slightly"
        QualityIssue.LANDMARK_COUNT_INVALID -> "Your face could not be detected — try again"
        QualityIssue.FACE_TOO_SMALL -> "Move a little closer"
        QualityIssue.FACE_TOO_LARGE -> "Move a little further back"
        QualityIssue.MULTIPLE_FACES -> "More than one face is in frame"
        QualityIssue.TOO_MUCH_ROLL -> "Straighten your head"
        QualityIssue.TOO_MUCH_YAW -> "Face the camera directly"
        QualityIssue.BAD_PITCH -> "Look straight at the camera"
        QualityIssue.EYES_CLOSED -> "Keep your eyes open"
        QualityIssue.TOO_DARK -> "Too dark — find more light"
        QualityIssue.TOO_BRIGHT -> "Too bright — step out of direct glare"
        QualityIssue.EXCESSIVE_MOTION -> "Hold still"
        QualityIssue.LOW_CONFIDENCE -> "Not quite clear yet"
    }
    Text(
        text = message,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    )
}

/**
 * Green means this frame is good. Amber is coaching. Red is only a real problem
 * (someone else in frame). A red ring on "move closer" or "turn your head" reads as a failure.
 */
private fun guideColorFor(issue: QualityIssue): Color = when (issue) {
    QualityIssue.OK -> Color(0xFF2E7D32)
    QualityIssue.MULTIPLE_FACES -> Color(0xFFB3261E)
    QualityIssue.POSITIONING -> Color.White
    else -> Color(0xFFFFC107)
}

/** Vertical stack for the guidance text above and below the camera preview. */
@Composable
fun CameraOverlayScaffold(
    modifier: Modifier = Modifier,
    hint: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            content()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            hint()
        }
    }
}
