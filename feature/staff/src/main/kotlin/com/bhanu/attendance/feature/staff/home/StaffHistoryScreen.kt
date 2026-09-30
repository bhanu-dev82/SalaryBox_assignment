package com.bhanu.attendance.feature.staff.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhanu.attendance.core.designsystem.accessibility.MergedSemantics
import com.bhanu.attendance.core.designsystem.accessibility.semanticHeading
import com.bhanu.attendance.core.designsystem.component.EmptyState
import com.bhanu.attendance.core.designsystem.component.InfoChip
import com.bhanu.attendance.core.designsystem.component.LoadingState
import com.bhanu.attendance.core.designsystem.component.SuccessChip
import com.bhanu.attendance.core.designsystem.component.WarningChip
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.feature.staff.R

/**
 * Attendance history.
 *
 * A **feed** layout, per the Material canonical layouts: `GridCells.Adaptive` means one column
 * on a compact phone, two or three on a tablet or an unfolded foldable, with no width-based
 * branching in this composable at all. `maxLineSpan` lets the date headers span the full grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffHistoryScreen(
    viewModel: StaffHomeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.historyState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadHistory(HistoryScope.MINE) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.staff_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))

            state.items.isEmpty() -> EmptyState(
                title = stringResource(R.string.staff_history_title),
                message = stringResource(R.string.staff_history_empty),
                icon = Icons.Filled.EventBusy,
                modifier = Modifier.padding(padding),
            )

            else -> LazyVerticalGrid(
                // Adaptive: the column count follows the available width automatically.
                columns = GridCells.Adaptive(minSize = 280.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("history_grid"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Stable keys, so inserting a newer punch does not recompose the whole grid.
                items(items = state.items, key = { it.id }) { item ->
                    HistoryCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(item: HistoryItem) {
    MergedSemantics(
        description = item.accessibilityLabel,
        modifier = Modifier.fillMaxWidth().testTag("history_item_${item.id}"),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.occurredAt.displayDateTime(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semanticHeading(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.punchType == PunchType.PUNCH_IN) {
                        SuccessChip(label = "In", accessibilityLabel = "Punched in")
                    } else {
                        WarningChip(label = "Out", accessibilityLabel = "Punched out")
                    }
                    InfoChip(
                        label = "${item.scorePercent}%",
                        accessibilityLabel = "Face match ${item.scorePercent} percent",
                    )
                }
                Text(
                    text = item.geofenceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
