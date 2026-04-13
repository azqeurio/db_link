package dev.dblink.core.logging

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * File logger for db link.
 * - Background file writing via coroutine
 * - Buffered batch writes for performance
 * - Log rotation for long sessions
 * - Session header with device/app info
 * - Uncaught exception handler
 */
object FileLogger {
    private const val TAG = "DbLink"
    private const val LOG_FILE_NAME = "camera_link_session.log"
    private const val OLD_LOG_FILE_NAME = "camera_link_session_prev.log"
    private const val MAX_LOG_SIZE_BYTES = 2 * 1024 * 1024L
    private const val FLUSH_INTERVAL_MS = 150L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var logFile: File? = null
    private var logDir: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val pendingLines = ConcurrentLinkedQueue<String>()
    @Volatile private var flushScheduled = false
    private var sessionId: String = ""

    fun initialize(context: Context) {
        logDir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(logDir!!, LOG_FILE_NAME)
        sessionId = System.currentTimeMillis().toString(36).takeLast(6)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val timestamp = dateFormat.format(Date())
                val crash = buildString {
                    appendLine()
                    appendLine("[$timestamp] [FATAL] [${thread.name}] ==== UNCAUGHT EXCEPTION ====")
                    appendLine("[$timestamp] [FATAL] [${thread.name}] ${throwable::class.qualifiedName}: ${throwable.message}")
                    throwable.stackTrace.forEach { frame ->
                        appendLine(
                            "[$timestamp] [FATAL] [${thread.name}]     at " +
                                "${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})",
                        )
                    }
                    var cause = throwable.cause
                    while (cause != null) {
                        appendLine(
                            "[$timestamp] [FATAL] [${thread.name}]   Caused by: " +
                                "${cause::class.qualifiedName}: ${cause.message}",
                        )
                        cause.stackTrace.take(5).forEach { frame ->
                            appendLine(
                                "[$timestamp] [FATAL] [${thread.name}]     at " +
                                    "${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})",
                            )
                        }
                        cause = cause.cause
                    }
                }
                logFile?.let { file ->
                    FileWriter(file, true).use { it.write(crash) }
                }
            } catch (_: Exception) {
                // Ignore logger failures while already crashing.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val header = buildString {
            appendLine()
            appendLine("==== SESSION [$sessionId] ====")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("App: ${context.packageName}")
            appendLine("Time: ${dateFormat.format(Date())}")
            appendLine()
        }
        scope.launch {
            try {
                val file = logFile ?: return@launch
                rotateIfNeeded(file)
                FileWriter(file, true).use { it.write(header) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write session header", e)
            }
        }
    }

    fun debug(message: String) = log("DEBUG", message)
    fun info(message: String) = log("INFO", message)
    fun warn(message: String) = log("WARN", message)

    fun error(message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) {
            "$message | ${throwable::class.simpleName}: ${throwable.message}"
        } else {
            message
        }
        log("ERROR", msg)
        if (throwable != null) {
            val trace = throwable.stackTrace.take(8).joinToString("\n    at ") {
                "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }
            log("ERROR", "  Stacktrace:\n    at $trace")
        }
    }

    private fun log(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        val formattedMessage = "[$timestamp] [$level] [$thread] $message\n"

        when (level) {
            "DEBUG" -> Log.d(TAG, message)
            "INFO" -> Log.i(TAG, message)
            "WARN" -> Log.w(TAG, message)
            "ERROR" -> Log.e(TAG, message)
        }

        pendingLines.add(formattedMessage)
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            try {
                delay(FLUSH_INTERVAL_MS)
                flushPending()
            } finally {
                flushScheduled = false
            }
        }
    }

    private fun flushPending() {
        val file = logFile ?: return
        val lines = buildString {
            while (true) {
                val line = pendingLines.poll() ?: break
                append(line)
            }
        }
        if (lines.isEmpty()) return

        try {
            rotateIfNeeded(file)
            BufferedWriter(FileWriter(file, true)).use { writer ->
                writer.write(lines)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists()) return
        if (file.length() < MAX_LOG_SIZE_BYTES) return
        try {
            val dir = logDir ?: return
            val oldFile = File(dir, OLD_LOG_FILE_NAME)
            if (oldFile.exists()) oldFile.delete()
            file.renameTo(oldFile)
        } catch (e: Exception) {
            Log.e(TAG, "Log rotation failed", e)
        }
    }

    fun getLogFile(): File? = logFile

    fun getAllLogFiles(): List<File> {
        val files = mutableListOf<File>()
        logDir?.let { dir ->
            val prev = File(dir, OLD_LOG_FILE_NAME)
            if (prev.exists()) files.add(prev)
        }
        logFile?.let { if (it.exists()) files.add(it) }
        return files
    }

    fun clearLogs() {
        scope.launch {
            try {
                logFile?.delete()
                logDir?.let { File(it, OLD_LOG_FILE_NAME).delete() }
                info("Logs cleared.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }
}
