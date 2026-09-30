package com.bhanu.attendance.core.designsystem.adaptive

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One navigation shell for every screen.
 *
 * `NavigationSuite` switches itself between a bottom bar, a short bottom bar, a rail and a
 * drawer based on the window size class, so a single implementation covers compact phones,
 * unfolded foldables, tablets and desktop-sized windows. Writing per-screen navigation
 * variants by hand is how apps end up with a stretched bottom bar on a tablet.
 *
 * Note the V2 window API: `currentWindowAdaptiveInfo()` is deprecated in material3-adaptive
 * 1.3.0, replaced by `currentWindowAdaptiveInfoV2()`, which also understands the L and XL
 * width classes. The library chooses the layout internally, so this wrapper deliberately does
 * not read the size class itself.
 */
@Composable
fun AdaptiveNavigationScaffold(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    items: List<AdaptiveNavItem>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scaffoldState = rememberNavigationSuiteScaffoldState()
    // Referenced so the V2 API is initialised eagerly; `NavigationSuite` reads it internally
    // but reading it here also makes the dependency explicit for anyone auditing layout
    // switching behaviour.
    @Suppress("UNUSED_EXPRESSION")
    currentWindowAdaptiveInfoV2()

    NavigationSuiteScaffold(
        state = scaffoldState,
        navigationSuiteItems = {
            items.forEachIndexed { index, item ->
                item(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    icon = { item.icon() },
                    label = { item.label() },
                )
            }
        },
        content = content,
    )
}

/** A destination in the adaptive navigation shell. */
data class AdaptiveNavItem(
    val label: () -> Unit,
    val icon: () -> Unit,
)

/**
 * Width breakpoints, in dp, matching the Material 3 window size class definitions.
 *
 * Named here rather than read from the library so the same numbers can be used in
 * `@Preview` configurations, which cannot call composable size-class APIs.
 */
object WindowBreakpoints {
    const val COMPACT_MAX_DP: Int = 599
    const val MEDIUM_MIN_DP: Int = 600
    const val MEDIUM_MAX_DP: Int = 839
    const val EXPANDED_MIN_DP: Int = 840
}
