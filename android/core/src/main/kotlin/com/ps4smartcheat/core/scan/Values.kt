package com.ps4smartcheat.core.scan

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.pow

/** Numeric value types supported by the scanner. All values are little-endian in PS4 memory. */
enum class ValueType(val size: Int, val label: String, val isFloat: Boolean) {
    INT8(1, "Int8", false),
    INT16(2, "Int16", false),
    INT32(4, "Int32", false),
    INT64(8, "Int64", false),
    FLOAT32(4, "Float", true),
    FLOAT64(8, "Double", true);

    /** Signed range of the type (ints only). */
    val signedMin: Long get() = if (this == INT64) Long.MIN_VALUE else -(1L shl (size * 8 - 1))
    val signedMax: Long get() = if (this == INT64) Long.MAX_VALUE else (1L shl (size * 8 - 1)) - 1
}

/** A user-entered value, parsed for a specific [ValueType]. */
sealed interface ParsedValue {
    val type: ValueType
    val text: String

    /** Integer value in canonical signed form (sign-extended to 64 bits). */
    data class IntValue(override val type: ValueType, override val text: String, val value: Long) : ParsedValue

    /** Floating value with the number of decimals the user typed (used for rounded matching). */
    data class FloatValue(override val type: ValueType, override val text: String, val value: Double, val decimals: Int) :
        ParsedValue
}

class ValueParseException(message: String) : IllegalArgumentException(message)

object ValueCodec {

    /**
     * Parses [text] for [type].
     * Integers accept decimal (signed, or unsigned up to the type width) and hexadecimal with `0x`.
     * Unsigned input is converted to the signed representation with the same bit pattern
     * (e.g. Int8 "255" == -1).
     */
    fun parse(type: ValueType, text: String): ParsedValue {
        val t = text.trim()
        if (t.isEmpty()) throw ValueParseException("Enter a value")
        return if (type.isFloat) parseFloat(type, t) else parseInt(type, t)
    }

    private fun parseInt(type: ValueType, t: String): ParsedValue.IntValue {
        val neg = t.startsWith("-")
        val body = t.removePrefix("-").removePrefix("+")
        val big = try {
            if (body.startsWith("0x", ignoreCase = true)) BigInteger(body.substring(2), 16) else BigInteger(body, 10)
        } catch (e: NumberFormatException) {
            throw ValueParseException("\"$t\" is not a valid ${type.label} integer")
        }.let { if (neg) it.negate() else it }
        val bits = type.size * 8
        val signedMin = BigInteger.ONE.shiftLeft(bits - 1).negate()
        val unsignedMax = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        if (big < signedMin || big > unsignedMax) {
            throw ValueParseException("$t is out of range for ${type.label} ($signedMin..$unsignedMax)")
        }
        return ParsedValue.IntValue(type, t, signExtend(big.toLong(), type))
    }

    private fun parseFloat(type: ValueType, t: String): ParsedValue.FloatValue {
        if (t.contains("x", ignoreCase = true) || t.contains("n", ignoreCase = true) || t.contains("i", ignoreCase = true)) {
            throw ValueParseException("\"$t\" is not a valid ${type.label}")
        }
        val v = t.toDoubleOrNull() ?: throw ValueParseException("\"$t\" is not a valid ${type.label}")
        if (!v.isFinite()) throw ValueParseException("Value must be finite")
        if (type == ValueType.FLOAT32 && abs(v) > Float.MAX_VALUE) throw ValueParseException("$t is out of range for Float")
        val mantissa = t.substringBefore('e').substringBefore('E')
        val decimals = if (mantissa.contains('.')) mantissa.substringAfter('.').length else 0
        return ParsedValue.FloatValue(type, t, v, decimals.coerceAtMost(15))
    }

    fun signExtend(v: Long, type: ValueType): Long = when (type) {
        ValueType.INT8 -> v.toByte().toLong()
        ValueType.INT16 -> v.toShort().toLong()
        ValueType.INT32 -> v.toInt().toLong()
        else -> v
    }

    fun encode(value: ParsedValue): ByteArray = when (value) {
        is ParsedValue.IntValue -> encodeLong(value.type, value.value)
        is ParsedValue.FloatValue -> encodeDouble(value.type, value.value)
    }

    fun encodeLong(type: ValueType, v: Long): ByteArray = ByteArray(type.size) { i -> (v ushr (8 * i)).toByte() }

    fun encodeDouble(type: ValueType, v: Double): ByteArray = when (type) {
        ValueType.FLOAT32 -> encodeLong(ValueType.INT32, java.lang.Float.floatToRawIntBits(v.toFloat()).toLong())
        ValueType.FLOAT64 -> encodeLong(ValueType.INT64, java.lang.Double.doubleToRawLongBits(v))
        else -> encodeLong(type, v.toLong())
    }

    /** Raw little-endian bits, zero-extended. */
    fun rawBits(type: ValueType, b: ByteArray, off: Int): Long {
        var r = 0L
        for (i in type.size - 1 downTo 0) r = (r shl 8) or (b[off + i].toLong() and 0xFF)
        return r
    }

    fun decodeLong(type: ValueType, b: ByteArray, off: Int): Long = signExtend(rawBits(type, b, off), type)

    fun decodeDouble(type: ValueType, b: ByteArray, off: Int): Double = when (type) {
        ValueType.FLOAT32 -> java.lang.Float.intBitsToFloat(rawBits(type, b, off).toInt()).toDouble()
        ValueType.FLOAT64 -> java.lang.Double.longBitsToDouble(rawBits(type, b, off))
        else -> decodeLong(type, b, off).toDouble()
    }

    /** Human readable value. Ints are shown signed. */
    fun format(type: ValueType, b: ByteArray, off: Int = 0): String = when (type) {
        ValueType.FLOAT32 -> java.lang.Float.intBitsToFloat(rawBits(type, b, off).toInt()).toString()
        ValueType.FLOAT64 -> java.lang.Double.longBitsToDouble(rawBits(type, b, off)).toString()
        else -> decodeLong(type, b, off).toString()
    }

    /** CE-style "rounded" comparison: [actual] matches when it rounds to [target] at [decimals] places. */
    fun roundedEquals(actual: Double, target: Double, decimals: Int): Boolean {
        if (!actual.isFinite()) return false
        val half = 0.5 * 10.0.pow(-decimals)
        return abs(actual - target) < half + 1e-12 * maxOf(1.0, abs(target))
    }
}
