package com.bhanu.attendance.core.common

import android.content.Context

/**
 * Holds the application [Context] for the few components that genuinely need it before
 * dependency injection is available — the uncaught-exception handler and the crash log store,
 * both of which must be installed from `Application.onCreate` and cannot be injected.
 *
 * A `Context` is kept, not an `Activity`, so nothing can leak a window or a view hierarchy.
 */
object AppContextHolder {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The application context.
     *
     * @throws IllegalStateException if called before [install]. Failing loudly is correct
     * here: returning null would push a null check into every caller and hide a genuine
     * initialisation-order bug.
     *
     * Note this holder is *not* usable from the Hilt graph. Injection happens inside
     * `Application.super.onCreate()`, strictly before a subclass `onCreate` body runs, so
     * anything the graph needs must come from Hilt's own `@ApplicationContext` binding.
     */
    fun require(): Context = appContext
        ?: error("AppContextHolder.install() was not called from Application.onCreate()")

    fun peek(): Context? = appContext
}
