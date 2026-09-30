package com.bhanu.attendance.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bhanu.attendance.core.designsystem.component.LoadingState
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.feature.admin.enrol.EnrolmentRoute
import com.bhanu.attendance.feature.admin.staff.StaffListRoute
import com.bhanu.attendance.feature.auth.ui.LoginRoute
import com.bhanu.attendance.feature.staff.home.StaffHomeRoute
import com.bhanu.attendance.feature.staff.verify.VerifyRoute
import com.bhanu.attendance.session.SessionViewModel

/**
 * The whole navigation graph.
 *
 * Fade transitions only, and short ones. A face-capture screen should feel immediate; a
 * 300ms slide on the way into a camera preview reads as the app being slow exactly where
 * latency matters most.
 */
private const val TRANSITION_MILLIS = 150

@Composable
fun AppNavHost(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    navController: NavHostController = rememberNavController(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
) {
    // Read isLoaded from the *collected* value, not from a plain getter on the ViewModel.
    // A getter that reaches into a MutableStateFlow is invisible to Compose, so the screen
    // would never recompose when it flipped to true and the app would sit on "Starting"
    // forever. This was a real bug found by running the app on a device.
    val ui by sessionViewModel.state.collectAsStateWithLifecycle()
    val session = ui.session

    // While the persisted session is still being read, show a loading state rather than
    // briefly flashing the login screen at a user who is already signed in. Flashing login is
    // exactly the "automatically signed out" complaint the real app's reviews describe.
    if (!ui.isLoaded) {
        LoadingState(modifier = modifier, message = "Starting")
        return
    }

    // Frozen once the stored session has loaded. Recomputing it on later sign-in would
    // rebuild the graph and throw away the screen the user is on.
    val startDestination = remember(ui.isLoaded) {
        if (ui.isSignedIn) homeFor(ui.role) else Destination.Login.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { fadeIn(tween(TRANSITION_MILLIS)) },
        exitTransition = { fadeOut(tween(TRANSITION_MILLIS)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MILLIS)) },
        popExitTransition = { fadeOut(tween(TRANSITION_MILLIS)) },
    ) {
        composable(Destination.Login.route) {
            LoginRoute(
                onSignedIn = { role ->
                    // popUpTo(Login) inclusive so the back gesture cannot return to a
                    // login screen the user has already passed.
                    navController.navigate(homeFor(role)) {
                        popUpTo(Destination.Login.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Destination.StaffHome.route) {
            StaffHomeRoute(
                onOpenVerification = { staffId, punchType ->
                    navController.navigate(Destination.Verify.createRoute(staffId, punchType))
                },
                onOpenHistory = { navController.navigate(Destination.StaffHistory.route) },
                onSignOut = {
                    sessionViewModel.signOut()
                    navController.navigate(Destination.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Destination.StaffHistory.route) {
            // History shares the staff feature's ViewModel scope, so it is deliberately a thin
            // screen here rather than a second full screen module.
            val viewModel: com.bhanu.attendance.feature.staff.home.StaffHomeViewModel =
                hiltViewModel()
            com.bhanu.attendance.feature.staff.home.StaffHistoryScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Destination.AdminStaff.route) {
            StaffListRoute(
                onSelectStaff = { /* the list-detail pane split is handled inside the screen */ },
                onOpenEnrolment = { staffId, name ->
                    navController.navigate(
                        Destination.AdminEnrolment.createRoute(staffId, name)
                    )
                },
                onShowMessage = { /* snackbars are owned by the screen */ },
                onSignOut = {
                    sessionViewModel.signOut()
                    navController.navigate(Destination.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Destination.AdminEnrolment.route,
            arguments = listOf(
                navArgument(Destination.AdminEnrolment.ARG_STAFF_ID) { type = NavType.StringType },
                navArgument(Destination.AdminEnrolment.ARG_NAME) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val staffId = entry.arguments?.getString(Destination.AdminEnrolment.ARG_STAFF_ID).orEmpty()
            val name = entry.arguments?.getString(Destination.AdminEnrolment.ARG_NAME).orEmpty()
            EnrolmentRoute(
                staffId = staffId,
                staffName = name,
                adminId = Destination.AdminEnrolment.ADMIN_ID,
                onFinished = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(
            route = Destination.Verify.route,
            arguments = listOf(
                navArgument(Destination.Verify.ARG_STAFF_ID) { type = NavType.StringType },
                navArgument(Destination.Verify.ARG_PUNCH) {
                    type = NavType.StringType
                    defaultValue = PunchType.PUNCH_IN.name
                },
            ),
        ) { entry ->
            val staffId = entry.arguments?.getString(Destination.Verify.ARG_STAFF_ID).orEmpty()
            val punch = entry.arguments?.getString(Destination.Verify.ARG_PUNCH)
                ?.let { runCatching { PunchType.valueOf(it) }.getOrNull() }
                ?: PunchType.PUNCH_IN
            VerifyRoute(
                staffId = staffId,
                punchType = punch,
                onRecorded = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }
    }
}
