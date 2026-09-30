package com.bhanu.attendance.core.designsystem.accessibility

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Material accessibility minimum for an interactive target.
 *
 * 48dp is roughly a 9mm fingertip contact patch. It is a *minimum*, not a suggestion: a
 * 24dp icon button is unusable with a tremor or in a moving vehicle.
 */
val MinTouchTarget: Dp = 48.dp

/**
 * Enforces a minimum interactive size.
 *
 * `IconButton` already does this, but only while its own defaults are intact — overriding
 * `MaterialTapTargetSize` or using a bare `Modifier.clickable` silently drops below the
 * minimum. Applied explicitly anywhere a custom control is built.
 */
fun Modifier.minimumTouchTarget(size: Dp = MinTouchTarget): Modifier =
    this.sizeIn(minWidth = size, minHeight = size)

/**
 * Marks an element as a heading so TalkBack can jump between sections.
 *
 * Without this, a screen-reader user hears a flat wall of text with no structure, which is the
 * most common cause of an app feeling unusable with TalkBack.
 */
fun Modifier.semanticHeading(): Modifier = semantics { heading() }

/**
 * Replaces an entire subtree's semantics with a single description.
 *
 * Use for composite components — a stat tile, an attendance row — where the default traversal
 * would make the user swipe through four or five fragments to understand one thing. Note this
 * is *not* `hideFromAccessibility`, which keeps semantics for tests: here the description
 * genuinely is the whole meaning of the component.
 */
fun Modifier.describeAs(description: String): Modifier =
    clearAndSetSemantics { contentDescription = description }

/**
 * Hides purely decorative content from accessibility services.
 *
 * `contentDescription = null` on an `Image`/`Icon` is the usual way to do this and is
 * preferable where the element is a single node. This is for decorative subtrees.
 */
fun Modifier.decorative(): Modifier = clearAndSetSemantics { }

/**
 * Applies a semantic description to a container *and* its children.
 *
 * `mergeDescendants = true` collapses a logical group into one focus stop. Careful: a parent
 * cannot merge children that merge themselves — a nested `IconButton` or `Button` inside a
 * clickable `Row` silently defeats the merge, and the user ends up with the worse of both
 * worlds. Audit for nested clickables before relying on this.
 */
@Composable
fun MergedSemantics(
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        CompositionLocalProvider(LocalContentColor provides LocalContentColor.current, content = content)
    }
}
