package com.ps4smartcheat.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, WARN, ERROR }

data class LogLine(val time: Long, val level: LogLevel, val text: String) {
    fun format(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(time)) + " " + level.name.first() + " " + text
}

/**
 * In-memory diagnostics log (not persisted, never sent anywhere) plus a stream of user-facing messages.
 * Nothing secret passes through here: ps4debug has no credentials.
 */
class AppLog(private val capacity: Int = 300) {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Short messages shown as snackbars. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun info(text: String) = add(LogLevel.INFO, text)
    fun warn(text: String) = add(LogLevel.WARN, text)

    fun error(text: String, notifyUser: Boolean = true) {
        add(LogLevel.ERROR, text)
        if (notifyUser) _messages.tryEmit(text)
    }

    fun notify(text: String) {
        add(LogLevel.INFO, text)
        _messages.tryEmit(text)
    }

    fun clear() { _lines.value = emptyList() }

    private fun add(level: LogLevel, text: String) = _lines.update { (it + LogLine(System.currentTimeMillis(), level, text)).takeLast(capacity) }
}
