package com.bhanu.attendance.feature.admin.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width as layoutWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.allVerticalHingeBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhanu.attendance.core.designsystem.accessibility.MergedSemantics
import com.bhanu.attendance.core.designsystem.accessibility.semanticHeading
import com.bhanu.attendance.core.designsystem.component.EmptyState
import com.bhanu.attendance.core.designsystem.component.ErrorCard
import com.bhanu.attendance.core.designsystem.component.InfoChip
import com.bhanu.attendance.core.designsystem.component.SuccessChip
import com.bhanu.attendance.feature.admin.R
import com.bhanu.attendance.domain.outcome.AppError
import androidx.activity.compose.BackHandler

/**
 * Staff management as an adaptive list-detail layout.
 *
 * The parent (`StaffListDetailScaffold` in the app module) owns the pane split, because
 * deciding between one pane and two is a window-size concern and the canonical
 * `ListDetailPaneScaffold` needs to see the real `WindowAdaptiveInfo`. This composable is
 * deliberately pane-agnostic: it works identically as a single pane on a phone and as a list
 * pane on a tablet, which is what keeps the two paths from drifting apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * Staff management as an adaptive list-detail layout.
 *
 * Follows the documented canonical list-detail semantics:
 *
 *  - **Expanded width** shows the list and the detail side by side; selecting a row updates
 *    the detail pane.
 *  - **Compact width** shows the list, and selecting a row shows the detail *in place of* the
 *    list; the back gesture returns to the list.
 *
 * Using the window size class directly (via `currentWindowAdaptiveInfoV2()`, which is not
 * experimental and understands the L/XL classes) rather than `ListDetailPaneScaffold`. The
 * scaffold would own pane visibility itself, but it also owns navigation between panes, and
 * this screen already holds the selection in a ViewModel that survives configuration
 * changes — which is what actually keeps the selection stable when a foldable folds and the
 * Activity is recreated. Handing that state to the scaffold would mean giving it up.
 */
@Composable
fun StaffListRoute(
    onSelectStaff: (String?) -> Unit,
    onOpenEnrolment: (staffId: String, staffName: String) -> Unit,
    onShowMessage: (String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StaffListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail by viewModel.detailState.collectAsStateWithLifecycle()
    val addState by viewModel.addStaffState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Two panes only when there is genuinely room. The hinge is a foldable's physical
    // boundary, so the split is nudged to avoid placing content across it.
    val windowInfo = currentWindowAdaptiveInfoV2()
    val widthDp = windowInfo.windowSizeClass.minWidthDp
    val isTwoPane = widthDp >= MEDIUM_WIDTH_BREAKPOINT_DP
    val verticalHinge = windowInfo.windowPosture.allVerticalHingeBounds.firstOrNull()

    val pinResetTarget by viewModel.pinResetTarget.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                StaffListEvent.OpenAddDialog,
                StaffListEvent.CloseAddDialog,
                -> Unit // driven by the dialog's own state

                is StaffListEvent.SelectStaff -> onSelectStaff(event.id)
                is StaffListEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                // The pin-reset target is state now, so the event is a no-op here.
                is StaffListEvent.ShowPinReset -> viewModel.openPinReset(event.staffId)
                is StaffListEvent.OpenEnrolment ->
                    detail.staff?.let { onOpenEnrolment(it.id, it.name) }
            }
        }
    }

    // On compact width the detail replaces the list; the back gesture returns to the list,
    // which is the documented canonical behaviour for a single-pane window.
    if (!isTwoPane && detail.staff != null) {
        BackHandler { viewModel.selectStaff(null) }
        StaffDetailPane(
            state = detail,
            onEnrolFace = { detail.staff?.let { onOpenEnrolment(it.id, it.name) } },
            onResetPin = { detail.staff?.let { viewModel.openPinReset(it.id) } },
            onToggleActive = { active -> detail.staff?.let { viewModel.setActive(it.id, active) } },
            onBack = { viewModel.selectStaff(null) },
            modifier = modifier,
        )
        return
    }

    Row(modifier = modifier.fillMaxSize()) {
        StaffListPane(
            state = state,
            onQueryChange = viewModel::onQueryChange,
            onStaffClick = { viewModel.selectStaff(it) },
            onAddClick = viewModel::openAddDialog,
            modifier = Modifier.weight(if (isTwoPane) LIST_PANE_WEIGHT else 1f),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.admin_title)) },
                    actions = {
                        OutlinedButton(
                            onClick = onSignOut,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .testTag("admin_sign_out"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.admin_sign_out),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        )

        if (isTwoPane) {
            // Inset by the hinge when one is present so neither pane is split across it.
            val hingeWidth = verticalHinge?.let {
                with(LocalDensity.current) { it.width.toDp() }
            } ?: 0.dp
            Spacer(modifier = Modifier.layoutWidth(hingeWidth))

            StaffDetailPane(
                state = detail,
                onEnrolFace = { detail.staff?.let { onOpenEnrolment(it.id, it.name) } },
                onResetPin = { detail.staff?.let { viewModel.openPinReset(it.id) } },
                onToggleActive = { active -> detail.staff?.let { viewModel.setActive(it.id, active) } },
                modifier = Modifier.weight(1f - LIST_PANE_WEIGHT),
            )
        }
    }

    if (state.isAddDialogVisible) {
        AddStaffDialog(
            state = addState,
            onDismiss = viewModel::closeAddDialog,
            onSubmit = viewModel::submitAddStaff,
        )
    }

    pinResetTarget?.let { staffId ->
        ResetPinDialog(
            onDismiss = viewModel::closePinReset,
            onConfirm = { pin ->
                viewModel.resetPin(staffId, pin)
                viewModel.closePinReset()
            },
        )
    }
}

/** Material 3 width breakpoint: 600dp. */
private const val MEDIUM_WIDTH_BREAKPOINT_DP = 600

/** The list keeps the smaller share; the detail is the working surface. */
private const val LIST_PANE_WEIGHT = 0.42f

/** The list pane. Renders as the only pane on a compact window, or the left pane when wide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffListPane(
    state: StaffListUiState,
    onQueryChange: (String) -> Unit,
    onStaffClick: (String) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = topBar,
        snackbarHost = snackbarHost,
        floatingActionButton = {
            // Hidden while the empty state is showing its own "Add staff" action, so the
            // screen does not offer the same action twice.
            if (state.items.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onAddClick,
                    modifier = Modifier.testTag("admin_fab_add"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.admin_add_staff))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.admin_search)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Search,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("admin_search"),
            )

            when {
                state.isLoading -> Box(Modifier.fillMaxSize())

                state.showEmptyState && state.isFiltered -> EmptyState(
                    title = stringResource(R.string.admin_empty_filtered_title),
                    message = stringResource(R.string.admin_empty_filtered_message),
                    icon = Icons.Filled.Search,
                )

                state.showEmptyState -> EmptyState(
                    title = stringResource(R.string.admin_empty_title),
                    message = stringResource(R.string.admin_empty_message),
                    icon = Icons.Filled.Badge,
                    actionLabel = stringResource(R.string.admin_add_staff),
                    onAction = onAddClick,
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("admin_staff_list"),
                    // contentPadding rather than padding, so a row is not hidden under the
                    // navigation bar.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // A stable key per staff member: without it, inserting a new member
                    // recomposes every row below it.
                    items(items = state.visibleItems, key = { it.id }) { item ->
                        StaffRow(item = item, onClick = { onStaffClick(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StaffRow(item: StaffListItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MergedSemantics(
        description = item.accessibilityLabel,
        modifier = modifier
            .fillMaxWidth()
            .testTag("staff_row_${item.id}"),
    ) {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { selected = item.isActive },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = item.employeeId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.isFaceEnrolled) {
                        SuccessChip(
                            label = stringResource(R.string.admin_enrolled),
                            accessibilityLabel = "Face enrolled",
                        )
                    } else {
                        InfoChip(
                            label = stringResource(R.string.admin_not_enrolled),
                            accessibilityLabel = "Face not enrolled",
                        )
                    }
                    if (!item.isActive) {
                        InfoChip(label = "Deactivated", accessibilityLabel = "Account deactivated")
                    }
                }
            }
        }
    }
}

/** Detail pane. Shows a placeholder when nothing is selected, as the canonical layout requires. */
@Composable
fun StaffDetailPane(
    state: StaffDetailUiState,
    onEnrolFace: () -> Unit,
    onResetPin: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val staff = state.staff
    if (staff == null) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Badge,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.admin_select_staff),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semanticHeading(),
            )
        }
        return
    }

    // Back is only meaningful when this is the only pane on screen; on a wide window both
    // panes are visible and a back arrow would be misleading.
    BackHandler(enabled = onBack != null) { onBack?.invoke() }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("admin_staff_detail"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = { onBack?.invoke() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to staff list",
                        )
                    }
                }
                Text(
                    text = staff.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semanticHeading(),
                )
            }
        }

        item {
            Text(
                text = staff.employeeId,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (staff.isFaceEnrolled) {
                    SuccessChip(label = stringResource(R.string.admin_enrolled))
                } else {
                    InfoChip(label = stringResource(R.string.admin_not_enrolled))
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.admin_attendance_count, state.punchCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onEnrolFace,
                    modifier = Modifier.fillMaxWidth().testTag("admin_enrol_face_button"),
                ) {
                    Text(
                        stringResource(
                            if (staff.isFaceEnrolled) R.string.admin_reenrol_face
                            else R.string.admin_enrol_face
                        )
                    )
                }
                TextButton(onClick = onResetPin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.admin_reset_pin))
                }
                TextButton(
                    onClick = { onToggleActive(!staff.isActive) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (staff.isActive) R.string.admin_deactivate else R.string.admin_activate
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun AddStaffDialog(
    state: AddStaffUiState,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var employeeId by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_add_staff)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.admin_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("add_staff_name"),
                )
                OutlinedTextField(
                    value = employeeId,
                    onValueChange = { employeeId = it },
                    label = { Text(stringResource(R.string.admin_employee_id)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("add_staff_employee_id"),
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text(stringResource(R.string.admin_initial_pin)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("add_staff_pin"),
                )
                if (state.errorMessage != null) {
                    ErrorCard(error = AppError.Unexpected(state.errorMessage, null))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(name, employeeId, pin) },
                enabled = state.canSubmit,
                modifier = Modifier.testTag("add_staff_confirm"),
            ) {
                Text(stringResource(R.string.admin_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.admin_cancel)) }
        },
    )
}

@Composable
private fun ResetPinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_reset_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "The new PIN must be given to the staff member directly. It is stored only as a PBKDF2 hash.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text(stringResource(R.string.admin_initial_pin)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("reset_pin_field"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin) },
                modifier = Modifier.testTag("reset_pin_confirm"),
            ) {
                Text("Reset")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.admin_cancel)) } },
    )
}
