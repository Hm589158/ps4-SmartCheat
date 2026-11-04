package com.ps4smartcheat.app.data

import com.ps4smartcheat.core.logging.Event
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.logging.EventLevel
import com.ps4smartcheat.core.logging.EventSink
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

/** One diagnostics entry: a structured [Event]. */
data class LogLine(val event: Event) {
    val level: EventLevel get() = event.level

    fun format(): String = buildString {
        append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(event.timeMillis)))
        append(' ').append(event.level.name.first()).append(' ')
        append('[').append(event.category.name.lowercase()).append("] ")
        append(event.message)
        if (event.fields.isNotEmpty()) append(" ").append(event.fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }
}

/**
 * In-memory structured diagnostics log (never persisted or sent anywhere; exportable by the user as
 * JSON Lines) plus a stream of user-facing messages. Nothing secret passes through here.
 */
class AppLog(private val capacity: Int = 500) : EventSink {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Short messages shown as snackbars. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    override fun emit(event: Event) = _lines.update { (it + LogLine(event)).takeLast(capacity) }

    fun event(level: EventLevel, category: EventCategory, message: String, vararg fields: Pair<String, Any?>) =
        emit(Event(System.currentTimeMillis(), level, category, message, fields.associate { (k, v) -> k to v.toString() }))

    fun info(text: String, category: EventCategory = EventCategory.APP) = event(EventLevel.INFO, category, text)
    fun warn(text: String, category: EventCategory = EventCategory.APP) = event(EventLevel.WARN, category, text)

    fun error(text: String, notifyUser: Boolean = true, category: EventCategory = EventCategory.APP) {
        event(EventLevel.ERROR, category, text)
        if (notifyUser) _messages.tryEmit(text)
    }

    fun notify(text: String, category: EventCategory = EventCategory.APP) {
        event(EventLevel.INFO, category, text)
        _messages.tryEmit(text)
    }

    fun clear() { _lines.value = emptyList() }

    /** All entries as JSON Lines (one object per line). */
    fun exportJsonLines(): String = _lines.value.joinToString("\n", postfix = "\n") { it.event.toJsonLine() }
}
