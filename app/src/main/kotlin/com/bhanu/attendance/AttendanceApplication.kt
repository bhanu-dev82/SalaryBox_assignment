package com.bhanu.attendance

import android.app.Application
import com.bhanu.attendance.core.common.AppContextHolder
import com.bhanu.attendance.core.common.crash.CrashHandler
import com.bhanu.attendance.data.di.ApplicationScope
import com.bhanu.attendance.data.seed.DatabaseSeeder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Three things must happen before any screen exists, and none of them can wait for the DI
 * graph:
 *
 *  1. [AppContextHolder.install] — the crash handler and log store need a file directory, and
 *     the uncaught-exception handler has to be in place before the first frame.
 *  2. [CrashHandler.install] — installed as early as possible, because a crash during
 *     initialisation is exactly the kind that is hardest to diagnose without a report.
 *  3. [DatabaseSeeder.seedIfEmpty] — without a seeded admin, a fresh install opens to a login
 *     screen nobody can satisfy.
 */
@HiltAndroidApp
class AttendanceApplication : Application() {

    @Inject
    lateinit var crashHandler: CrashHandler

    @Inject
    lateinit var seeder: DatabaseSeeder

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // First, because everything else may log.
        AppContextHolder.install(this)
        crashHandler.install()

        applicationScope.launch {
            seeder.seedIfEmpty()
        }
    }
}
