package com.bhanu.attendance.feature.admin.enrol

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhanu.attendance.core.designsystem.accessibility.semanticHeading
import com.bhanu.attendance.core.designsystem.camera.CameraFailure
import com.bhanu.attendance.core.designsystem.camera.FaceCameraSession
import com.bhanu.attendance.core.designsystem.camera.hasCameraPermission
import com.bhanu.attendance.core.designsystem.component.CameraOverlayScaffold
import com.bhanu.attendance.core.designsystem.component.CaptureHint
import com.bhanu.attendance.core.designsystem.component.ErrorCard
import com.bhanu.attendance.core.designsystem.component.FaceGuideOverlay
import com.bhanu.attendance.core.designsystem.theme.BhanuTheme
import com.bhanu.attendance.domain.face.QualityIssue
import com.bhanu.attendance.feature.admin.R

/**
 * Guided face enrolment.
 *
 * Permission is requested in-context, when the user has actually reached a screen that needs
 * it, with the rationale visible. Requesting on launch is the pattern Android's own guidance
 * warns against, and it is the reason users learn to tap "deny" reflexively.
 */
@Composable
fun EnrolmentRoute(
    staffId: String,
    staffName: String,
    adminId: String,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnrolmentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = granted
            if (!granted) {
                viewModel.onCameraFailure("Camera permission was declined.")
            }
        },
    )

    LaunchedEffect(staffId, staffName) {
        viewModel.start(staffId, staffName, adminId)
        viewModel.observeFrames()
    }

    // Built by the ViewModel so both share one FaceEngine instance. Recreated only if the
    // staff member changes, and released in DisposableEffect below.
    val session = remember(staffId) { viewModel.createCameraSession(context.applicationContext) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { session.previewView },
                modifier = Modifier.fillMaxSize(),
            )
            LaunchedEffect(session) {
                session.bind(
                    lifecycleOwner = lifecycleOwner,
                    onFrame = { bitmap -> viewModel.submitBitmap(bitmap) },
                    onFailure = { failure ->
                        viewModel.onCameraFailure(
                            when (failure) {
                                is CameraFailure.Bind -> failure.detail
                                CameraFailure.NoCamera -> "No front camera is available on this device."
                                CameraFailure.CaptureFailed -> "The photo could not be captured."
                            }
                        )
                    },
                )
            }
            DisposableEffect(session) {
                onDispose {
                    viewModel.abandon("Cancelled before enrolment completed")
                    session.release()
                }
            }

            FaceGuideOverlay(
                issue = if (state.isSaved) QualityIssue.OK else state.currentIssue,
                progress = state.fraction,
                modifier = Modifier.fillMaxSize(),
            )

            CameraOverlayScaffold(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                hint = {
                    if (state.isSaved) {
                        EnrolmentCompleteHint(state = state, onDone = onFinished)
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(horizontal = 24.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.admin_enrol_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                modifier = Modifier.semanticHeading(),
                            )
                            Text(
                                text = state.staffName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                            // liveRegion so TalkBack announces each accepted sample; without
                            // it a screen-reader user gets no feedback at all until the end.
                            LinearProgressIndicator(
                                progress = { state.fraction },
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.25f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = "Sample ${state.accepted} of ${state.target}"
                                    },
                            )
                            Text(
                                text = stringResource(
                                    R.string.admin_enrol_progress, state.accepted, state.target
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                            CaptureHint(issue = state.currentIssue)
                        }
                    }
                },
                content = {},
            )
        } else {
            PermissionRationale(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onCancel = onCancel,
                modifier = Modifier.fillMaxSize(),
            )
        }

        state.error?.let { error ->
            ErrorCard(
                error = error,
                onRetry = if (hasPermission) {
                    { viewModel.dismissError() }
                } else {
                    null
                },
                onDismiss = viewModel::dismissError,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun EnrolmentCompleteHint(state: EnrolmentUiState, onDone: () -> Unit) {
    val status = BhanuTheme.status
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = status.success,
        )
        Text(
            text = stringResource(R.string.admin_enrol_complete),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        // Showing the derived threshold is deliberate: it makes the self-calibration visible
        // instead of being a hidden magic number, and tells the admin when an enrolment was
        // poor enough to loosen the bar.
        state.thresholdPreview?.let { threshold ->
            Text(
                text = "Match threshold set to ${"%.3f".format(threshold)}" +
                    (state.intraClassSimilarity?.let { " from a sample quality of ${"%.3f".format(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onDone, modifier = Modifier.testTag("enrolment_done")) {
            Text(stringResource(R.string.admin_enrol_done))
        }
    }
}

@Composable
private fun PermissionRationale(
    onGrant: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.admin_enrol_permission),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semanticHeading(),
        )
        Row(
            modifier = Modifier.padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onCancel) { Text(stringResource(R.string.admin_cancel)) }
            Button(
                onClick = onGrant,
                modifier = Modifier.testTag("enrolment_grant_permission"),
            ) {
                Text(stringResource(R.string.admin_enrol_grant))
            }
        }
    }
}
