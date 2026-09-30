package com.bhanu.attendance.core.common.logging

import android.util.Log
import com.bhanu.attendance.core.common.AppContextHolder

/**
 * Logcat logger, used in debug builds only.
 *
 * Verbosity is driven by the APK's own `FLAG_DEBUGGABLE`, so a release build cannot
 * accidentally ship logs. Tags are truncated to the 23-character limit logcat enforces —
 * exceeding it throws at runtime, which is a crash caused purely by a long tag.
 */
class LogcatLogger(private val enabled: Boolean = AppContextHolder.isDebuggable) : AppLogger {

    override fun d(tag: String, message: String) {
        if (enabled) Log.d(normaliseTag(tag), message)
    }

    override fun i(tag: String, message: String) {
        if (enabled) Log.i(normaliseTag(tag), message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (enabled) Log.w(normaliseTag(tag), message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (enabled) Log.e(normaliseTag(tag), message, throwable)
    }

    private fun normaliseTag(tag: String): String =
        if (tag.length <= MAX_TAG_LENGTH) tag else tag.substring(0, MAX_TAG_LENGTH)

    private companion object {
        const val MAX_TAG_LENGTH = 23
    }
}
