package com.bhanu.attendance.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bhanu.attendance.domain.outcome.AppError

/**
 * Renders a typed [AppError] as something a person can act on.
 *
 * This component is the reason [AppError] is a sealed class rather than a `Result`. Every
 * failure the domain can produce has a specific, human sentence here, plus a retry affordance
 * where retrying could plausibly work. No error path in this app degrades to a bare
 * "something went wrong".
 *
 * The `technicalDetail` line is user-visible on purpose: when someone does report a problem,
 * "ValidationException at FaceEngine.kt:112" is worth more than "it didn't work", and it
 * contains no personal data because the app never puts any in a message.
 */
@Composable
fun ErrorCard(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    technicalDetail: String? = null,
) {
    val description = buildString {
        append(error.message)
        if (onRetry != null) append(". You can try again.")
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (technicalDetail != null) {
                Text(
                    text = technicalDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (onRetry != null || onDismiss != null) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                }
            }
        }
    }
}
