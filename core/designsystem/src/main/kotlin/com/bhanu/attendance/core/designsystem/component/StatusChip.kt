package com.bhanu.attendance.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.bhanu.attendance.core.designsystem.accessibility.MergedSemantics
import com.bhanu.attendance.core.designsystem.theme.BhanuTheme

/**
 * A small status pill.
 *
 * The whole chip is one accessibility node with a single description, because a status like
 * "Punched in at 09:12" read as separate icon and text fragments is announced as two
 * disconnected pieces. The colour is never the only signal — the label carries the same
 * information, so the chip still works for a colour-blind user or on a monochrome screen.
 */
@Composable
fun StatusChip(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accessibilityLabel: String = label,
) {
    MergedSemantics(
        description = accessibilityLabel,
        modifier = modifier,
    ) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        // Decorative: the merged parent already announces the meaning, so a
                        // second contentDescription here would be read twice.
                        contentDescription = null,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Success/positive pill, using the semantic status colours rather than the theme's primary. */
@Composable
fun SuccessChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accessibilityLabel: String = label,
) {
    val status = BhanuTheme.status
    StatusChip(
        label = label,
        containerColor = status.successContainer,
        contentColor = status.onSuccessContainer,
        modifier = modifier,
        icon = icon,
        accessibilityLabel = accessibilityLabel,
    )
}

/** Warning pill: outside the geofence, punch-out disabled, and so on. */
@Composable
fun WarningChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accessibilityLabel: String = label,
) {
    val status = BhanuTheme.status
    StatusChip(
        label = label,
        containerColor = status.warningContainer,
        contentColor = status.onWarningContainer,
        modifier = modifier,
        icon = icon,
        accessibilityLabel = accessibilityLabel,
    )
}

/** Neutral pill for informational state. */
@Composable
fun InfoChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accessibilityLabel: String = label,
) {
    StatusChip(
        label = label,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        icon = icon,
        accessibilityLabel = accessibilityLabel,
    )
}
