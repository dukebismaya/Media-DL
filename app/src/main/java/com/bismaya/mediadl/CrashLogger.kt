package com.bismaya.mediadl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global crash logger that catches uncaught exceptions and writes
 * detailed logs to a file. Logs can then be shared/copied from the
 * in-app crash debugger screen.
 *
 * Usage:  CrashLogger.install(applicationContext)
 *
 * Logs are stored in: app_internal/crash_logs/
 * Each crash creates a timestamped file with full stack trace + device info.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val LOG_DIR = "crash_logs"
    private const val MAX_LOGS = 50    // keep last 50 crashes

    private const val PREFS_NAME = "crash_logger_prefs"
    private const val KEY_PENDING_CRASH = "pending_crash"
    private const val KEY_PENDING_CRASH_TS = "pending_crash_ts"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var logDir: File
    private lateinit var prefs: SharedPreferences

    /**
     * Install as the global uncaught exception handler.
     * Call this in Application.onCreate().
     */
    fun install(context: Context) {
        logDir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        // Recover any pending crash that wasn't written to file (fast kill scenario)
        recoverPendingCrash()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Step 1: Fast write to SharedPreferences (survives fast process kill)
                val log = buildLogEntry(context, thread, throwable, fatal = true)
                prefs.edit()
                    .putString(KEY_PENDING_CRASH, log)
                    .putLong(KEY_PENDING_CRASH_TS, System.currentTimeMillis())
                    .commit()  // commit() is synchronous — essential here

                // Step 2: Write to file (may not complete if process is killed)
                writeCrashLog(context, thread, throwable)

                // Step 3: Clear pending since file was written
                prefs.edit().remove(KEY_PENDING_CRASH).remove(KEY_PENDING_CRASH_TS).commit()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }
            // Forward to default handler (system crash dialog / kill)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.d(TAG, "CrashLogger installed, log dir: ${logDir.absolutePath}")
    }

    /**
     * Recover a crash log that was saved to SharedPreferences but not to file
     * (happens when the process was killed before file I/O completed).
     */
    private fun recoverPendingCrash() {
        try {
            val pending = prefs.getString(KEY_PENDING_CRASH, null)
            if (!pending.isNullOrBlank()) {
                val file = createLogFile("crash_recovered")
                file.writeText(pending)
                trimLogs()
                prefs.edit().remove(KEY_PENDING_CRASH).remove(KEY_PENDING_CRASH_TS).apply()
                Log.w(TAG, "Recovered pending crash log to ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recover pending crash", e)
        }
    }

    /**
     * Manually log an exception (non-fatal). Useful for caught exceptions
     * you still want recorded.
     */
    fun logException(context: Context, tag: String, throwable: Throwable) {
        try {
            val log = buildLogEntry(context, Thread.currentThread(), throwable, fatal = false, extraTag = tag)
            val file = createLogFile("nonfatal")
            file.writeText(log)
            trimLogs()
            Log.w(TAG, "Non-fatal exception logged to ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log non-fatal exception", e)
        }
    }

    // ── Read crash logs ─────────────────────────────────────────────────────────

    data class CrashLog(
        val fileName: String,
        val timestamp: Long,
        val title: String,       // first line of exception
        val content: String,     // full log
        val isFatal: Boolean
    )

    /**
     * Returns all crash logs, newest first.
     */
    fun getCrashLogs(context: Context): List<CrashLog> {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                try {
                    val content = file.readText()
                    val isFatal = file.name.startsWith("crash_")
                    val title = content.lineSequence()
                        .firstOrNull { it.startsWith("Exception:") }
                        ?.removePrefix("Exception: ")
                        ?: content.lineSequence()
                            .firstOrNull { it.contains("Exception") || it.contains("Error") }
                        ?: "Unknown error"
                    CrashLog(
                        fileName = file.name,
                        timestamp = file.lastModified(),
                        title = title.take(120),
                        content = content,
                        isFatal = isFatal
                    )
                } catch (_: Exception) { null }
            }
            ?: emptyList()
    }

    /**
     * Delete all crash logs.
     */
    fun clearLogs(context: Context) {
        val dir = File(context.filesDir, LOG_DIR)
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Delete a specific log.
     */
    fun deleteLog(context: Context, fileName: String) {
        File(File(context.filesDir, LOG_DIR), fileName).delete()
    }

    /**
     * Share a crash log via Android share sheet.
     */
    fun shareCrashLog(context: Context, log: CrashLog) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Media-DL Crash Report: ${log.title}")
            putExtra(Intent.EXTRA_TEXT, log.content)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share crash log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Copy crash log text to clipboard.
     */
    fun copyToClipboard(context: Context, log: CrashLog) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", log.content))
    }

    // ── Internal ────────────────────────────────────────────────────────────────

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val log = buildLogEntry(context, thread, throwable, fatal = true)
        val file = createLogFile("crash")
        file.writeText(log)
        trimLogs()
        Log.e(TAG, "FATAL crash logged to ${file.name}")
    }

    private fun buildLogEntry(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        fatal: Boolean,
        extraTag: String? = null
    ): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val now = sdf.format(Date())

        val pm = context.packageManager
        val pi = try {
            pm.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) { null }

        return buildString {
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("  MEDIA-DL CRASH REPORT")
            appendLine("  ${if (fatal) "FATAL" else "NON-FATAL"} ${extraTag?.let { "[$it]" } ?: ""}")
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine()
            appendLine("Timestamp: $now")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine()
            appendLine("── App Info ──────────────────────────────────────────────────")
            appendLine("Package: ${context.packageName}")
            appendLine("Version: ${pi?.versionName ?: "?"} (code ${pi?.longVersionCode ?: "?"})")
            appendLine()
            appendLine("── Device Info ───────────────────────────────────────────────")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine("── Memory ────────────────────────────────────────────────────")
            val runtime = Runtime.getRuntime()
            val used = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val max = runtime.maxMemory() / 1024 / 1024
            val free = runtime.freeMemory() / 1024 / 1024
            appendLine("Heap used: ${used}MB / ${max}MB (free: ${free}MB)")
            appendLine()
            appendLine("── Exception ─────────────────────────────────────────────────")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            appendLine("── Stack Trace ───────────────────────────────────────────────")
            appendLine(stackTrace)

            // Include cause chain
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 5) {
                appendLine()
                appendLine("── Caused by (depth ${depth + 1}) ──────────────────────────────")
                appendLine("${cause.javaClass.name}: ${cause.message}")
                val csw = StringWriter()
                cause.printStackTrace(PrintWriter(csw))
                appendLine(csw.toString())
                cause = cause.cause
                depth++
            }

            appendLine()
            appendLine("═══════════════════════════════════════════════════════════════")
        }
    }

    private fun createLogFile(prefix: String): File {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val name = "${prefix}_${sdf.format(Date())}.log"
        return File(logDir, name)
    }

    private fun trimLogs() {
        val files = logDir.listFiles()
            ?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        if (files.size > MAX_LOGS) {
            files.drop(MAX_LOGS).forEach { it.delete() }
        }
    }
}
