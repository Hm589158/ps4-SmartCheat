package com.ps4smartcheat.core.tools

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.scan.ProgressListener
import com.ps4smartcheat.core.scan.ScanProgress
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class PatternParseException(message: String) : IllegalArgumentException(message)

/**
 * A byte pattern. Each position matches when `(byte & mask) == value`; a position may also accept an
 * alternative value ([alt], used for case-insensitive ASCII text).
 */
class BytePattern private constructor(
    val values: ByteArray,
    val masks: ByteArray,
    private val alt: ByteArray?,
    val description: String,
) {
    val length: Int get() = values.size

    fun matchesAt(buf: ByteArray, off: Int): Boolean {
        for (i in values.indices) {
            val b = buf[off + i]
            val m = masks[i]
            if ((b.toInt() and m.toInt()) != (values[i].toInt() and m.toInt())) {
                if (alt == null || b != alt[i]) return false
            }
        }
        return true
    }

    companion object {
        const val MAX_LENGTH = 256

        /**
         * Parses an "array of bytes" pattern: hex bytes separated by spaces, `??` (or `?`) for any byte,
         * nibble wildcards such as `4?` / `?F`. Example: `48 8B ?? ?? 89 05`.
         */
        fun hex(text: String): BytePattern {
            val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .flatMap { t -> if (t.length > 2 && t.none { it == '?' } && t.length % 2 == 0) t.chunked(2) else listOf(t) }
            if (tokens.isEmpty()) throw PatternParseException("Enter bytes, e.g. 48 8B ?? 05")
            if (tokens.size > MAX_LENGTH) throw PatternParseException("Pattern is longer than $MAX_LENGTH bytes")
            val values = ByteArray(tokens.size)
            val masks = ByteArray(tokens.size)
            tokens.forEachIndexed { i, raw ->
                val t = if (raw == "?") "??" else raw
                if (t.length != 2) throw PatternParseException("\"$raw\" is not a byte (use two hex digits or ??)")
                var v = 0
                var m = 0
                for ((k, ch) in t.withIndex()) {
                    val shift = if (k == 0) 4 else 0
                    if (ch == '?') continue
                    val d = Character.digit(ch, 16)
                    if (d < 0) throw PatternParseException("\"$raw\" is not a byte (use two hex digits or ??)")
                    v = v or (d shl shift)
                    m = m or (0xF shl shift)
                }
                values[i] = v.toByte()
                masks[i] = m.toByte()
            }
            if (masks.all { it.toInt() == 0 }) throw PatternParseException("The pattern needs at least one fixed byte")
            return BytePattern(values, masks, null, tokens.joinToString(" ") { if (it == "?") "??" else it.uppercase() })
        }

        enum class TextEncoding(val label: String) { UTF8("UTF-8 / ASCII"), UTF16LE("UTF-16LE") }

        /** Text search. [ignoreCase] applies to ASCII letters only. */
        fun text(text: String, encoding: TextEncoding, ignoreCase: Boolean): BytePattern {
            if (text.isEmpty()) throw PatternParseException("Enter text to search for")
            val bytes = when (encoding) {
                TextEncoding.UTF8 -> text.toByteArray(Charsets.UTF_8)
                TextEncoding.UTF16LE -> text.toByteArray(Charsets.UTF_16LE)
            }
            if (bytes.size > MAX_LENGTH) throw PatternParseException("Text is longer than $MAX_LENGTH bytes")
            val masks = ByteArray(bytes.size) { 0xFF.toByte() }
            val alt = if (ignoreCase) ByteArray(bytes.size) { i ->
                val c = bytes[i].toInt() and 0xFF
                when (c.toChar()) {
                    in 'a'..'z' -> (c - 32).toByte()
                    in 'A'..'Z' -> (c + 32).toByte()
                    else -> bytes[i]
                }
            } else null
            return BytePattern(bytes, masks, alt, "\"$text\" (${encoding.label}${if (ignoreCase) ", any case" else ""})")
        }
    }
}

data class PatternHit(val address: Long, val bytes: ByteArray)

data class PatternSearchResult(val pattern: String, val hits: List<PatternHit>, val truncated: Boolean, val bytesScanned: Long)

/**
 * One-shot search for a [BytePattern] in [regions] using CMD_PROC_READ chunks. Chunks overlap by
 * `pattern.length - 1` bytes so matches across chunk borders are found exactly once; matches never span two
 * regions. Runs on Android (no server-side pattern command is used).
 */
object PatternSearch {
    suspend fun search(
        backend: DebugBackend,
        pid: Int,
        regions: List<MemoryRegion>,
        pattern: BytePattern,
        maxHits: Int = 10_000,
        chunkSize: Int = 1 shl 20,
        alignment: Int = 1,
        progress: ProgressListener = {},
    ): PatternSearchResult {
        require(chunkSize > pattern.length) { "chunk too small" }
        require(alignment in 1..16) { "alignment must be 1..16" }
        val hits = ArrayList<PatternHit>()
        val total = regions.sumOf { it.size }
        var done = 0L
        val len = pattern.length
        progress(ScanProgress(0, total, 0, "Searching"))
        for (r in regions) {
            if (!r.isValid || !r.isReadable || r.size < len) { done += r.size; continue }
            // Next candidate start; each read begins there, so a match crossing a chunk end is found in the next read.
            var c = r.start + ((alignment - java.lang.Long.remainderUnsigned(r.start, alignment.toLong())) % alignment)
            while (c + len <= r.end) {
                coroutineContext.ensureActive()
                val readLen = minOf(chunkSize.toLong(), r.end - c).toInt()
                val buf = backend.readMemory(pid, c, readLen)
                var off = 0
                while (off + len <= readLen) {
                    if (pattern.matchesAt(buf, off)) {
                        hits += PatternHit(c + off, buf.copyOfRange(off, off + len))
                        if (hits.size >= maxHits) {
                            return PatternSearchResult(pattern.description, hits, true, done + (c + off - r.start))
                        }
                    }
                    off += alignment
                }
                c += off
                progress(ScanProgress(done + (c - r.start).coerceAtMost(r.size), total, hits.size.toLong(), "Searching"))
            }
            done += r.size
        }
        return PatternSearchResult(pattern.description, hits, false, done)
    }
}
