package com.bhanu.attendance.feature.staff.verify

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhanu.attendance.core.designsystem.accessibility.semanticHeading
import com.bhanu.attendance.core.designsystem.camera.CameraFailure
import com.bhanu.attendance.core.designsystem.camera.hasCameraPermission
import com.bhanu.attendance.core.designsystem.component.CaptureHint
import com.bhanu.attendance.core.designsystem.component.ErrorCard
import com.bhanu.attendance.core.designsystem.component.FaceGuideOverlay
import com.bhanu.attendance.domain.model.GeoLocation
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.feature.staff.R
import java.util.Locale

/**
 * Face verification and punch capture.
 *
 * Location is requested from a button, not from a side effect. A permission dialog launched
 * while the camera is opening is dropped by the system, and the punch was then stored with
 * no place. Denying location still records attendance; the record simply has no fix.
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

    var hasCamera by remember { mutableStateOf(context.hasCameraPermission()) }
    var hasLocation by remember { mutableStateOf(context.hasLocationPermission()) }
    var locationServicesOn by remember { mutableStateOf(context.isDeviceLocationEnabled()) }
    var locationSkipped by remember { mutableStateOf(false) }
    var locationDenied by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCamera = granted
        cameraDenied = !granted
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocation = granted
        locationServicesOn = context.isDeviceLocationEnabled()
        locationDenied = !granted
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasCamera = context.hasCameraPermission()
        hasLocation = context.hasLocationPermission()
        locationServicesOn = context.isDeviceLocationEnabled()
    }

    LaunchedEffect(staffId, punchType) {
        viewModel.start(staffId, punchType)
        viewModel.observeFrames()
    }

    val willSaveLocation = hasLocation && locationServicesOn && !locationSkipped
    val explainFirst = state.stage == VerifyStage.VERIFYING && when (state.error) {
        is AppError.NotEnrolled, is AppError.StaffNotFound -> true
        is AppError.Unexpected -> state.matchedFrames == 0 && state.capturedJpeg == null
        else -> false
    }
    val locationSettled = locationSkipped || willSaveLocation
    val runCamera = state.stage == VerifyStage.VERIFYING && hasCamera && locationSettled && !explainFirst

    LaunchedEffect(willSaveLocation, explainFirst, state.stage) {
        // Warm the radio only once a punch is actually possible, and not on the
        // not-enrolled message.
        if (willSaveLocation && !explainFirst && state.stage == VerifyStage.VERIFYING) {
            viewModel.prepareLocation()
        }
    }

    val session = remember(staffId) { viewModel.createCameraSession(context.applicationContext) }

    LaunchedEffect(session, runCamera, state.cameraGeneration, lifecycleOwner) {
        if (!runCamera) {
            session.unbind()
            return@LaunchedEffect
        }
        session.bind(
            lifecycleOwner = lifecycleOwner,
            onFrame = { bitmap -> viewModel.onAnalysedBitmap(bitmap) },
            onFailure = viewModel::onCameraFailure,
        )
    }

    DisposableEffect(session) {
        onDispose { session.release() }
    }

    fun leave() {
        if (state.isRecording) return
        if (state.stage == VerifyStage.RECORDED) {
            onRecorded(state.punchType)
        } else {
            viewModel.giveUp()
            onCancel()
        }
    }

    BackHandler { leave() }

    val title = when (state.stage) {
        VerifyStage.VERIFYING -> if (punchType == PunchType.PUNCH_IN) {
            stringResource(R.string.staff_punch_in)
        } else {
            stringResource(R.string.staff_punch_out)
        }
        VerifyStage.AWAITING_CONFIRMATION -> stringResource(R.string.staff_verified)
        VerifyStage.RECORDED -> stringResource(R.string.staff_punch_recorded)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semanticHeading()) },
                navigationIcon = {
                    IconButton(onClick = { leave() }, enabled = !state.isRecording) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Go back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            explainFirst -> BlockedPane(
                message = state.error?.message.orEmpty(),
                onBack = { leave() },
                modifier = Modifier.padding(innerPadding),
            )

            state.stage == VerifyStage.VERIFYING && !hasCamera -> CameraPermissionPane(
                deniedForGood = cameraDenied && !context.canAskCameraAgain(),
                onGrant = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
                modifier = Modifier.padding(innerPadding),
            )

            state.stage == VerifyStage.VERIFYING && !hasLocation && !locationSkipped -> LocationPermissionPane(
                denied = locationDenied,
                deniedForGood = locationDenied && !context.canAskLocationAgain(),
                onAllow = { locationPermissionLauncher.launch(LOCATION_PERMISSIONS) },
                onOpenSettings = { context.openAppSettings() },
                onSkip = { locationSkipped = true },
                modifier = Modifier.padding(innerPadding),
            )

            state.stage == VerifyStage.VERIFYING && hasLocation && !locationServicesOn && !locationSkipped ->
                LocationServicesPane(
                    onTurnOn = { context.openLocationSettings() },
                    onSkip = { locationSkipped = true },
                    modifier = Modifier.padding(innerPadding),
                )

            state.stage == VerifyStage.VERIFYING -> CameraPane(
                state = state,
                sessionPreview = { session.previewView },
                onRetry = viewModel::retry,
                onDismissError = viewModel::dismissError,
                modifier = Modifier.padding(innerPadding),
            )

            state.stage == VerifyStage.AWAITING_CONFIRMATION -> ConfirmPane(
                state = state,
                willSaveLocation = willSaveLocation,
                onConfirm = viewModel::confirm,
                onRetry = viewModel::retry,
                onDismissError = viewModel::dismissError,
                modifier = Modifier.padding(innerPadding),
            )

            else -> RecordedPane(
                location = state.savedLocation,
                onDone = { onRecorded(state.punchType) },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun CameraPane(
    state: VerifyUiState,
    sessionPreview: () -> android.view.View,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        ) {
            AndroidView(factory = { sessionPreview() }, modifier = Modifier.fillMaxSize())
            FaceGuideOverlay(issue = state.issue, modifier = Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CaptureHint(
                issue = state.issue,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription =
                            "Matched ${state.matchedFrames} of ${state.requiredMatches} checks"
                    },
            )
            Text(
                text = "Matched ${state.matchedFrames} of ${state.requiredMatches} checks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let { error ->
                ErrorCard(
                    error = error,
                    onRetry = onRetry,
                    onDismiss = onDismissError,
                )
            }
        }
    }
}

@Composable
private fun ConfirmPane(
    state: VerifyUiState,
    willSaveLocation: Boolean,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val jpeg = state.capturedJpeg
        if (jpeg != null) {
            val bitmap = remember(jpeg) {
                android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "The photo that will be stored with this punch",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .testTag("verify_captured_selfie"),
                )
            }
        }
        Text(
            text = "Face matched at ${"%.0f".format(state.bestScore * 100)}%",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp).semanticHeading(),
        )
        Text(
            text = stringResource(R.string.staff_check_photo),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(
                if (willSaveLocation) R.string.staff_location_will_save
                else R.string.staff_location_will_skip,
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (state.isBusy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.semantics {
                    contentDescription = "Saving"
                },
            )
            Text(
                text = stringResource(
                    if (willSaveLocation) R.string.staff_saving_with_location
                    else R.string.staff_saving,
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        state.error?.let { error ->
            ErrorCard(
                error = error,
                onRetry = if (state.isBusy) null else onRetry,
                onDismiss = onDismissError,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Button(
            onClick = onConfirm,
            enabled = !state.isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .testTag("verify_confirm"),
        ) {
            Text(stringResource(R.string.staff_record_punch))
        }
        OutlinedButton(
            onClick = onRetry,
            enabled = !state.isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.staff_retake))
        }
    }
}

@Composable
private fun RecordedPane(
    location: GeoLocation?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.staff_punch_recorded),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semanticHeading(),
        )
        if (location != null) {
            val label = "%.5f, %.5f".format(Locale.US, location.latitude, location.longitude)
            Text(
                text = stringResource(R.string.staff_saved_at, label),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            location.accuracyMeters?.let { meters ->
                Text(
                    text = "Accurate to about ${meters.toInt().coerceAtLeast(1)} m",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.staff_saved_without_location),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Button(
            onClick = onDone,
            modifier = Modifier.padding(top = 24.dp).testTag("verify_done"),
        ) {
            Text(stringResource(R.string.staff_done))
        }
    }
}

@Composable
private fun BlockedPane(
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text("Go back")
        }
    }
}

@Composable
private fun CameraPermissionPane(
    deniedForGood: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GateColumn(modifier) {
        Text(
            text = stringResource(
                if (deniedForGood) R.string.staff_camera_settings_body
                else R.string.staff_camera_permission,
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = if (deniedForGood) onOpenSettings else onGrant,
            modifier = Modifier.testTag("verify_grant_permission"),
        ) {
            Text(
                stringResource(
                    if (deniedForGood) R.string.staff_open_settings
                    else R.string.staff_grant_camera,
                ),
            )
        }
    }
}

@Composable
private fun LocationPermissionPane(
    denied: Boolean,
    deniedForGood: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GateColumn(modifier) {
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.staff_location_gate_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semanticHeading(),
        )
        Text(
            text = stringResource(
                when {
                    deniedForGood -> R.string.staff_location_settings_body
                    denied -> R.string.staff_location_denied_body
                    else -> R.string.staff_location_gate_body
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = if (deniedForGood) onOpenSettings else onAllow,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("verify_grant_location"),
        ) {
            Text(
                stringResource(
                    if (deniedForGood) R.string.staff_open_settings
                    else R.string.staff_allow_location,
                ),
            )
        }
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("verify_skip_location"),
        ) {
            Text(stringResource(R.string.staff_continue_without_location))
        }
    }
}

@Composable
private fun LocationServicesPane(
    onTurnOn: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GateColumn(modifier) {
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.staff_location_services_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semanticHeading(),
        )
        Text(
            text = stringResource(R.string.staff_location_services_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onTurnOn,
            modifier = Modifier.fillMaxWidth().testTag("verify_enable_location"),
        ) {
            Text(stringResource(R.string.staff_turn_on_location))
        }
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth().testTag("verify_skip_location"),
        ) {
            Text(stringResource(R.string.staff_continue_without_location))
        }
    }
}

@Composable
private fun GateColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        content()
    }
}

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun Context.hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

private fun Context.isDeviceLocationEnabled(): Boolean {
    val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    // Any one of these being on is enough. A single false reading used to stop the punch
    // even though the phone could still produce a fix.
    val fromManager = runCatching {
        manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && manager.isLocationEnabled
    }.getOrDefault(false)
    if (fromManager) return true
    val gps = runCatching { manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)
    val network = runCatching {
        manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }.getOrDefault(false)
    if (gps || network) return true
    val mode = runCatching {
        Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE)
    }.getOrDefault(Settings.Secure.LOCATION_MODE_OFF)
    return mode != Settings.Secure.LOCATION_MODE_OFF
}

private fun Context.canAskCameraAgain(): Boolean =
    findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: true

private fun Context.canAskLocationAgain(): Boolean {
    val activity = findActivity() ?: return true
    return activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
        activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
}

private fun Context.openAppSettings() {
    startAsActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        },
    )
}

private fun Context.openLocationSettings() {
    startAsActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

private fun Context.startAsActivity(intent: Intent) {
    val activity = findActivity()
    if (activity != null) {
        activity.startActivity(intent)
    } else {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
