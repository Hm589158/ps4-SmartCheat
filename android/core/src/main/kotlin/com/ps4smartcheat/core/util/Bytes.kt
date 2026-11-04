package com.ps4smartcheat.core.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Little-endian helpers. ps4debug uses native x86-64 (little-endian) layout for every field. */
object LE {
    fun u32(b: ByteArray, off: Int = 0): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    fun i32(b: ByteArray, off: Int = 0): Int = u32(b, off).toInt()

    fun u16(b: ByteArray, off: Int = 0): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun i64(b: ByteArray, off: Int = 0): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (b[off + i].toLong() and 0xFF)
        return r
    }

    fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun wrap(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
}

/** Reads a NUL-terminated ASCII string from a fixed-size field. */
fun ByteArray.cString(offset: Int, maxLen: Int): String {
    val end = (offset until (offset + maxLen).coerceAtMost(size)).firstOrNull { this[it] == 0.toByte() }
        ?: (offset + maxLen).coerceAtMost(size)
    return String(this, offset, end - offset, Charsets.US_ASCII)
}

object Hex {
    private val DIGITS = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray, separator: String = ""): String {
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        bytes.forEachIndexed { i, b ->
            if (i > 0) sb.append(separator)
            sb.append(DIGITS[(b.toInt() shr 4) and 0xF]).append(DIGITS[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    /** Decodes a hex byte string. Whitespace is ignored. Throws [IllegalArgumentException] on bad input. */
    fun decode(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "Hex string must have an even number of digits" }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[2 * i], 16)
            val lo = Character.digit(clean[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex digit in \"$text\"" }
            ((hi shl 4) or lo).toByte()
        }
    }
}

/** Addresses are carried as [Long] holding an unsigned 64-bit value. */
object Address {
    /** Highest user-space address on PS4 (FreeBSD amd64 user VA limit). */
    const val USER_MAX: Long = 0x0000_8000_0000_0000L

    fun format(address: Long): String = "0x" + java.lang.Long.toHexString(address).uppercase().padStart(9, '0')

    /** Parses "0x1234", "1234" (hex) — addresses are always hexadecimal. */
    fun parse(text: String): Long? {
        val t = text.trim().removePrefix("0x").removePrefix("0X")
        if (t.isEmpty() || t.length > 16) return null
        return try {
            java.lang.Long.parseUnsignedLong(t, 16)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Unsigned `a < b`. */
    fun lt(a: Long, b: Long): Boolean = java.lang.Long.compareUnsigned(a, b) < 0
    fun le(a: Long, b: Long): Boolean = java.lang.Long.compareUnsigned(a, b) <= 0
}
