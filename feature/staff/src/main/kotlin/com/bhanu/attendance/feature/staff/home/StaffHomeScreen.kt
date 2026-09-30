package com.bhanu.attendance.feature.staff.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhanu.attendance.core.designsystem.accessibility.MergedSemantics
import com.bhanu.attendance.core.designsystem.accessibility.semanticHeading
import com.bhanu.attendance.core.designsystem.component.InfoChip
import com.bhanu.attendance.core.designsystem.component.LoadingState
import com.bhanu.attendance.core.designsystem.component.SuccessChip
import com.bhanu.attendance.core.designsystem.component.WarningChip
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.domain.usecase.PunchAvailability
import com.bhanu.attendance.feature.staff.R

/**
 * Employee home.
 *
 * The primary action label is derived from [PunchRules] rather than hardcoded, so the button
 * can never disagree with what the repository will accept. That single rule object is the
 * reason there is no "already punched in" error path in this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffHomeRoute(
    onOpenVerification: (staffId: String, punchType: PunchType) -> Unit,
    onOpenHistory: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StaffHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is StaffHomeEvent.OpenVerification -> onOpenVerification(event.staffId, event.punchType)
                is StaffHomeEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.staff_title)) },
                actions = {
                    Row {
                        OutlinedButton(
                            onClick = onOpenHistory,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("staff_history_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.staff_history),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        OutlinedButton(
                            onClick = onSignOut,
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Logout,
                                contentDescription = "Sign out",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        StaffHomeContent(
            state = state,
            onPunch = viewModel::onPunchClicked,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // Scrollable so the layout survives a 3.5x font scale on a short screen
                // instead of clipping the button off the bottom.
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun StaffHomeContent(
    state: StaffHomeUiState,
    onPunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.staffName.isNotEmpty()) {
            Text(
                text = state.staffName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semanticHeading(),
            )
        }

        TodayCard(state = state)

        val availability = state.availability
        val blocked = availability as? PunchAvailability.Blocked
        if (blocked != null) {
            InfoChip(label = blocked.reason, accessibilityLabel = blocked.reason)
        }

        Button(
            onClick = onPunch,
            enabled = state.nextPunch != null && blocked == null,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("staff_punch_button"),
        ) {
            Icon(
                imageVector = Icons.Filled.Login,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = when {
                    // Blocked is not "done for the day". The chip above says why; the button
                    // stays the action they will take once that is resolved.
                    blocked != null -> stringResource(R.string.staff_punch_in)
                    state.nextPunch == PunchType.PUNCH_IN -> stringResource(R.string.staff_punch_in)
                    state.nextPunch == PunchType.PUNCH_OUT -> stringResource(R.string.staff_punch_out)
                    else -> stringResource(R.string.staff_shift_complete)
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // The primary action changes meaning, so it is announced rather than relying on the
        // screen-reader user noticing the label changed silently.
        if (state.nextPunch != null) {
            Text(
                text = if (state.nextPunch == PunchType.PUNCH_IN) {
                    "Ready to punch in"
                } else {
                    "Ready to punch out"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (state.summary?.punchOutEnabled == false) {
            Text(
                text = stringResource(R.string.staff_punch_out_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TodayCard(state: StaffHomeUiState, modifier: Modifier = Modifier) {
    // Bound to locals so the smart cast works: these are computed properties with custom
    // getters, and each call recomputes the formatting.
    val punchInLabel = state.punchInLabel
    val punchOutLabel = state.punchOutLabel
    val spoken = buildString {
        append(punchInLabel ?: "Not punched in yet today")
        if (state.punchOutLabel != null) append(". ${state.punchOutLabel}")
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("staff_today_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (punchInLabel != null) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Schedule
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp).semanticHeading(),
                )
            }

            MergedSemantics(description = spoken) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (punchInLabel != null) {
                        Text(
                            text = punchInLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        SuccessChip(
                            label = "Punched in",
                            icon = Icons.Filled.AccessTime,
                            accessibilityLabel = punchInLabel,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.staff_not_punched_in),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (punchOutLabel != null) {
                        Text(
                            text = punchOutLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        WarningChip(
                            label = "Punched out",
                            icon = Icons.Filled.AccessTime,
                            accessibilityLabel = punchOutLabel,
                        )
                    }
                }
            }
        }
    }
}
