package com.bhanu.attendance.core.common

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Whether the running APK is debuggable.
 *
 * Read from the installed app's own flags rather than a generated `BuildConfig`, so
 * `:core:common` and `:data` do not need build-config generation, and so the answer reflects
 * the APK actually installed rather than a build constant that could be stale.
 */
val Context.isDebuggable: Boolean
    get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
