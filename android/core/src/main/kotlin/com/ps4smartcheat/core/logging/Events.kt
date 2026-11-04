package com.ps4smartcheat.core.logging

enum class EventLevel { DEBUG, INFO, WARN, ERROR }

enum class EventCategory { CONNECTION, PROTOCOL, PROCESS, SCAN, EDIT, CHEAT, APP }

/** A structured diagnostics event. Never contains credentials (ps4debug has none) or memory contents. */
data class Event(
    val timeMillis: Long,
    val level: EventLevel,
    val category: EventCategory,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
) {
    /** One JSON object per line (JSON Lines). */
    fun toJsonLine(): String = buildString {
        append("{\"t\":").append(timeMillis)
        append(",\"level\":\"").append(level.name).append('"')
        append(",\"cat\":\"").append(category.name).append('"')
        append(",\"msg\":").append(quote(message))
        for ((k, v) in fields) append(',').append(quote(k)).append(':').append(quote(v))
        append('}')
    }

    companion object {
        fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}

/** Receives structured events from core components. */
fun interface EventSink {
    fun emit(event: Event)

    companion object {
        val NONE = EventSink { }
    }
}

fun EventSink.log(level: EventLevel, category: EventCategory, message: String, vararg fields: Pair<String, Any?>) =
    emit(Event(System.currentTimeMillis(), level, category, message, fields.associate { (k, v) -> k to v.toString() }))
