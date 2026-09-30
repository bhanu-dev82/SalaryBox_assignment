package com.bhanu.attendance.core.common.logging

/**
 * Logging seam.
 *
 * Two rules this interface exists to enforce:
 *  - debug builds emit to logcat, release builds emit nothing but the crash file.
 *  - no call site may log a PIN, a face descriptor, a selfie path or a raw coordinate. Face
 *    and credential material must never reach a log sink.
 */
interface AppLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

object NoOpLogger : AppLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
