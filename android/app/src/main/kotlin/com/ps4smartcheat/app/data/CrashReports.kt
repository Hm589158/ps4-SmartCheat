package com.ps4smartcheat.app.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash reports: when the app closes because of an unexpected error, the report (time, version, thread, stack
 * trace) is written to the app's private storage before Android ends the process; Diagnostics shows it at the next
 * start, to copy or share by hand. Nothing is sent anywhere (no telemetry).
 */
class CrashReports(dir: File, private val version: String) {
    private val file = File(dir, "last-crash.txt")

    /** Chains in front of the current default handler (which still ends the process). */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread.name, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report of the last crash, or null when there is none. */
    fun last(): String? = runCatching { file.takeIf { it.isFile }?.readText()?.ifBlank { null } }.getOrNull()

    fun dismiss() { runCatching { file.delete() } }

    fun report(thread: String, error: Throwable, now: Date = Date()): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            append("PS4 SmartCheat ").append(version).append(" crashed at ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)).append('\n')
            append("Thread: ").append(thread).append('\n')
            append(error.javaClass.name).append(": ").append(error.message ?: "").append("\n\n")
            append(trace.lineSequence().take(MAX_LINES).joinToString("\n"))
        }
    }

    private companion object { const val MAX_LINES = 120 }
}
