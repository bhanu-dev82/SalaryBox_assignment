package com.bhanu.attendance.nav

import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.domain.model.Role

/**
 * Every destination, with its arguments and route pattern in one place.
 *
 * Routes are built by named functions rather than string-concatenated at call sites, so a
 * renamed argument is a compile error instead of a runtime "route not found".
 */
sealed class Destination(val route: String) {

    data object Login : Destination("login")

    data object StaffHome : Destination("staff/home")

    data object StaffHistory : Destination("staff/history")

    data object AdminStaff : Destination("admin/staff")

    data object AdminEnrolment : Destination("admin/enrolment/{staffId}?name={name}") {
        const val ARG_STAFF_ID = "staffId"
        const val ARG_NAME = "name"
        const val ADMIN_ID = "admin"

        fun createRoute(staffId: String, name: String): String =
            "admin/enrolment/$staffId?name=${android.net.Uri.encode(name)}"
    }

    data object Verify : Destination("staff/verify/{staffId}?punch={punch}") {
        const val ARG_STAFF_ID = "staffId"
        const val ARG_PUNCH = "punch"

        fun createRoute(staffId: String, punchType: PunchType): String =
            "staff/verify/$staffId?punch=${punchType.name}"
    }
}

/** Where to land after sign-in. */
fun homeFor(role: Role): String = when (role) {
    Role.ADMIN -> Destination.AdminStaff.route
    Role.STAFF -> Destination.StaffHome.route
}
