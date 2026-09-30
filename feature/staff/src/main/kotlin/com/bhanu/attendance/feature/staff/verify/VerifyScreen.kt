package com.bhanu.attendance.feature.staff.verify

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhanu.attendance.core.designsystem.accessibility.semanticHeading
import com.bhanu.attendance.core.designsystem.camera.CameraFailure
import com.bhanu.attendance.core.designsystem.camera.hasCameraPermission
import com.bhanu.attendance.core.designsystem.component.CameraOverlayScaffold
import com.bhanu.attendance.core.designsystem.component.CaptureHint
import com.bhanu.attendance.core.designsystem.component.ErrorCard
import com.bhanu.attendance.core.designsystem.component.FaceGuideOverlay
import com.bhanu.attendance.domain.face.QualityIssue
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.feature.staff.R

/**
 * Face verification and punch capture.
 *
 * The captured selfie is shown back to the person before the record is written. It is their
 * attendance and their biometric data; letting them see and reject a bad capture is both
 * correct and the fastest way to explain a mis-match.
 */
@Composable
fun VerifyRoute(
    staffId: String,
    punchType: PunchType,
    onRecorded: (PunchType) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VerifyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = granted
            if (!granted) viewModel.onPermissionDenied()
        },
    )

    LaunchedEffect(staffId, punchType) {
        viewModel.start(staffId, punchType)
        viewModel.observeFrames()
    }

    val session = remember(staffId) { viewModel.createCameraSession(context.applicationContext) }

    LaunchedEffect(session, hasPermission) {
        if (hasPermission) {
            session.bind(
                lifecycleOwner = lifecycleOwner,
                onFrame = { bitmap -> viewModel.onAnalysedBitmap(bitmap) },
                onFailure = viewModel::onCameraFailure,
            )
        }
    }

    DisposableEffect(session) {
        onDispose { session.release() }
    }

    // Stop the camera the moment verification is decided; leaving it bound burns battery for
    // no reason while the person is confirming.
    LaunchedEffect(state.stage) {
        if (state.stage == VerifyStage.AWAITING_CONFIRMATION || state.stage == VerifyStage.RECORDED) {
            runCatching { session.release() }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (state.stage == VerifyStage.VERIFYING && hasPermission) {
            AndroidView(factory = { session.previewView }, modifier = Modifier.fillMaxSize())
            FaceGuideOverlay(
                issue = state.issue,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        viewModel.giveUp()
                        onCancel()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Cancel and go back",
                        tint = Color.White,
                    )
                }
                Text(
                    text = when (state.stage) {
                        VerifyStage.VERIFYING -> stringResource(R.string.staff_verify_title)
                        VerifyStage.AWAITING_CONFIRMATION -> stringResource(R.string.staff_verified)
                        VerifyStage.RECORDED -> stringResource(R.string.staff_verified)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.semanticHeading(),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.stage) {
                    VerifyStage.VERIFYING -> if (hasPermission) {
                        VerifyProgressPane(state)
                    } else {
                        CameraPermissionPane(
                            onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        )
                    }

                    VerifyStage.AWAITING_CONFIRMATION -> ConfirmPane(
                        state = state,
                        onConfirm = {
                            viewModel.confirm()
                            onRecorded(state.punchType)
                        },
                        onRetry = viewModel::retry,
                    )

                    VerifyStage.RECORDED -> RecordedPane(onDone = { onRecorded(state.punchType) })
                }
            }
        }

        state.error?.let { error ->
            ErrorCard(
                error = error,
                onRetry = if (hasPermission) viewModel::retry else null,
                onDismiss = viewModel::dismissError,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

@Composable
private fun VerifyProgressPane(state: VerifyUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Matched ${state.matchedFrames} of ${state.requiredMatches} frames"
                },
        )
        Text(
            text = "Matched $state.matchedFrames of $state.requiredMatches checks",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        CaptureHint(issue = state.issue)
    }
}

@Composable
private fun ConfirmPane(
    state: VerifyUiState,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
) {
    val jpeg = state.capturedJpeg
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (jpeg != null) {
            val bitmap = remember(jpeg) {
                android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?.asImageBitmap()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "The photo that will be stored with this punch",
                    modifier = Modifier
                        .size(180.dp)
                        .testTag("verify_captured_selfie")
                        .semantics(mergeDescendants = true) {},
                )
            }
        }
        Text(
            text = "Face matched at ${"%.1f".format(state.bestScore * 100)}%",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(top = 16.dp).semanticHeading(),
        )
        Text(
            text = "Check the photo, then record your punch.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Button(
            onClick = onConfirm,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("verify_confirm"),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).semantics { contentDescription = "Recording" },
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.staff_record_punch))
            }
        }
        OutlinedButton(
            onClick = onRetry,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Retake")
        }
    }
}

@Composable
private fun RecordedPane(onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Punch recorded",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.semanticHeading(),
        )
        Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) { Text("Done") }
    }
}

@Composable
private fun CameraPermissionPane(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Camera access is needed to verify your face.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.semanticHeading(),
        )
        Button(
            onClick = onGrant,
            modifier = Modifier.padding(top = 16.dp).testTag("verify_grant_permission"),
        ) {
            Text("Grant camera access")
        }
    }
}
