package com.bhanu.attendance.core.common.crash

import android.os.Build
import com.bhanu.attendance.core.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One captured crash, trimmed to what is safe to persist. */
data class CrashReport(
    val occurredAtMillis: Long,
    val threadName: String,
    val exceptionClass: String,
    val message: String?,
    val stackTrace: String,
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    /** Free-text breadcrumb of what the user was doing, e.g. `screen=MarkAttendance`. */
    val context: String?,
) {
    fun format(): String = buildString {
        appendLine("time: ${FORMAT.format(Date(occurredAtMillis))}")
        appendLine("app: $appVersion")
        appendLine("device: $deviceModel (Android $androidVersion)")
        appendLine("thread: $threadName")
        appendLine("context: ${context ?: "unknown"}")
        appendLine("exception: $exceptionClass")
        appendLine("message: ${message ?: "(none)"}")
        appendLine()
        appendLine("--- stack trace ---")
        append(stackTrace)
    }

    private companion object {
        val FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}

/** Persists crash reports to app-internal storage. */
interface CrashLogStore {
    /** Synchronous: the process is already dying, so there is nothing to suspend on. */
    fun write(report: CrashReport)
    fun latest(): CrashReport?
    fun clear()
}

/**
 * File-backed crash store.
 *
 * Reports go to `filesDir`, which is app-private and excluded from the media scanner, so
 * stack traces containing user data never become visible to other apps or to a file browser.
 *
 * Writing is deliberately best-effort and never throws: a failure inside the crash handler
 * must not replace the original crash with a different one.
 */
class FileCrashLogStore(
    private val directory: File,
    private val maxReports: Int = 10,
) : CrashLogStore {

    private val lock = Any()

    override fun write(report: CrashReport) {
        runCatching {
            synchronized(lock) {
                if (!directory.exists()) directory.mkdirs()
                val file = File(directory, "crash-${report.occurredAtMillis}.txt")
                file.writeText(report.format())
                prune()
            }
        }
    }

    override fun latest(): CrashReport? {
        val file = runCatching { newestFile() }.getOrNull() ?: return null
        return runCatching { parse(file.readText()) }.getOrNull()
    }

    override fun clear() {
        runCatching { synchronized(lock) { directory.listFiles()?.forEach { it.delete() } } }
    }

    private fun newestFile(): File? =
        directory.listFiles { f -> f.name.startsWith("crash-") }
            ?.maxByOrNull { it.lastModified() }

    /** Keeps only the most recent [maxReports] files so the directory cannot grow unbounded. */
    private fun prune() {
        val files = directory.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(maxReports).forEach { it.delete() }
    }

    private fun parse(text: String): CrashReport {
        val lines = text.lines()
        fun field(key: String): String? = lines
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter("$key:")?.trim()

        val trace = text.substringAfter("--- stack trace ---", text).trim()
        return CrashReport(
            occurredAtMillis = 0L,
            threadName = field("thread") ?: "unknown",
            exceptionClass = field("exception") ?: "unknown",
            message = field("message")?.takeIf { it != "(none)" },
            stackTrace = trace,
            appVersion = field("app") ?: "unknown",
            androidVersion = field("device")?.substringAfter("Android ", "") ?: "unknown",
            deviceModel = field("device") ?: "unknown",
            context = field("context")?.takeIf { it != "unknown" },
        )
    }
}

/**
 * Captures uncaught exceptions, writes a report, then hands control to the platform handler
 * so the process still dies normally.
 *
 * Note this takes its [CrashLogStore] as a constructor parameter rather than reaching for a
 * global context: the uncaught-exception handler has to be installed from
 * `Application.onCreate`, which is *after* Hilt's injection phase, so it cannot rely on a
 * context the DI graph has already resolved. The store is constructed in the graph instead
 * and injected here.
 *
 * ### Why this exists
 *
 * The public reviews for the real SalaryBox app repeatedly complain about crashes and
 * unhelpful failures: *"it automatically signs out and can't login, always show something went
 * wrong, whats going on, please fix this asap"*. A crash a user cannot explain is a support
 * ticket. So rather than dying silently, the app records what happened and explains itself on
 * the next launch, in plain language, with a copyable technical detail the user can hand to an
 * admin.
 *
 * @see CrashReporter for the user-facing side of this.
 */
class CrashHandler(
    private val store: CrashLogStore,
    private val appScope: CoroutineScope,
    private val logger: AppLogger,
    private val appVersion: String,
) : Thread.UncaughtExceptionHandler {

    /** Breadcrumb describing what the user was doing, updated as screens change. */
    @Volatile
    var currentContext: String? = null

    private var delegate: Thread.UncaughtExceptionHandler? = null

    fun install() {
        delegate = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // Never let a failure in here mask the original problem.
        runCatching {
            val report = CrashReport(
                occurredAtMillis = System.currentTimeMillis(),
                threadName = thread.name,
                exceptionClass = throwable.javaClass.name,
                message = throwable.message,
                stackTrace = throwable.stackTraceToString(),
                appVersion = appVersion,
                androidVersion = Build.VERSION.RELEASE ?: "unknown",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                context = currentContext,
            )
            store.write(report)
        }.onFailure { t ->
            logger.e(TAG, "Failed to persist crash report", t)
        }

        // Give any in-flight log write a moment, then defer to the platform so the process
        // terminates and the system can show its own ANR/died dialog.
        runCatching { Thread.sleep(FLUSH_GRACE_MILLIS) }
        delegate?.uncaughtException(thread, throwable)
    }

    /** Captures a caught exception without killing the process. Used for handled failures. */
    fun recordNonFatal(throwable: Throwable, context: String) {
        runCatching {
            appScope.launch {
                store.write(
                    CrashReport(
                        occurredAtMillis = System.currentTimeMillis(),
                        threadName = Thread.currentThread().name,
                        exceptionClass = throwable.javaClass.name,
                        message = throwable.message,
                        stackTrace = throwable.stackTraceToString(),
                        appVersion = appVersion,
                        androidVersion = Build.VERSION.RELEASE ?: "unknown",
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                        context = context,
                    )
                )
            }
        }
    }

    private companion object {
        const val TAG = "CrashHandler"
        const val FLUSH_GRACE_MILLIS = 120L
    }
}

internal fun Throwable.stackTraceToString(): String {
    val writer = StringWriter()
    PrintWriter(writer).use { printStackTrace(it) }
    return writer.toString()
}
