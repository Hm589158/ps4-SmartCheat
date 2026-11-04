package com.ps4smartcheat.core.scan

enum class ScanMode(
    val label: String,
    val needsValue: Boolean,
    val needsPrevious: Boolean,
    /** Takes a second value (upper bound of [BETWEEN]). */
    val needsSecondValue: Boolean = false,
) {
    EXACT("Exact value", true, false),
    UNKNOWN_INITIAL("Unknown initial value", false, false),
    BIGGER_THAN("Bigger than", true, false),
    SMALLER_THAN("Smaller than", true, false),
    /** Inclusive range; the bounds may be given in either order. */
    BETWEEN("Value between", true, false, needsSecondValue = true),
    INCREASED("Increased", false, true),
    DECREASED("Decreased", false, true),
    CHANGED("Changed", false, true),
    UNCHANGED("Unchanged", false, true),
    INCREASED_BY("Increased by", true, true),
    DECREASED_BY("Decreased by", true, true);

    companion object {
        val FIRST_SCAN_MODES = listOf(EXACT, BIGGER_THAN, SMALLER_THAN, BETWEEN, UNKNOWN_INITIAL)
        val NEXT_SCAN_MODES = listOf(EXACT, BIGGER_THAN, SMALLER_THAN, BETWEEN, INCREASED, DECREASED, CHANGED, UNCHANGED, INCREASED_BY, DECREASED_BY)
    }
}

/**
 * Evaluates one candidate value. Integer comparisons (increased/decreased) are signed.
 * Float "unchanged"/"changed" compare raw bits so NaN payloads and -0.0 are handled deterministically.
 */
class Matcher(val type: ValueType, val mode: ScanMode, val value: ParsedValue?, val value2: ParsedValue? = null) {
    private val exactBytes: ByteArray? =
        if (mode == ScanMode.EXACT && value is ParsedValue.IntValue) ValueCodec.encode(value) else null
    private val intArg = (value as? ParsedValue.IntValue)?.value
    private val floatArg = value as? ParsedValue.FloatValue
    private val size = type.size
    private val mask: Long = if (size == 8) -1L else (1L shl (size * 8)) - 1

    // Bounds for BIGGER_THAN / SMALLER_THAN / BETWEEN (signed ints or doubles), ordered low..high.
    private val lowInt: Long
    private val highInt: Long
    private val lowF: Double
    private val highF: Double

    init {
        require(!mode.needsValue || value != null) { "${mode.label} requires a value" }
        require(!mode.needsSecondValue || value2 != null) { "${mode.label} requires two values" }
        require(value == null || value.type == type) { "Value type mismatch" }
        require(value2 == null || value2.type == type) { "Value type mismatch" }
        val a = value?.let(::numeric) ?: 0.0
        val b = value2?.let(::numeric) ?: a
        val ai = (value as? ParsedValue.IntValue)?.value ?: 0
        val bi = (value2 as? ParsedValue.IntValue)?.value ?: ai
        lowInt = minOf(ai, bi); highInt = maxOf(ai, bi)
        lowF = minOf(a, b); highF = maxOf(a, b)
    }

    private fun numeric(v: ParsedValue): Double = when (v) {
        is ParsedValue.IntValue -> v.value.toDouble()
        is ParsedValue.FloatValue -> v.value
    }

    fun matches(cur: ByteArray, curOff: Int, prev: ByteArray?, prevOff: Int): Boolean {
        if (mode.needsPrevious) requireNotNull(prev) { "${mode.label} requires previous values" }
        return when (mode) {
            ScanMode.UNKNOWN_INITIAL -> true
            ScanMode.EXACT -> {
                val eb = exactBytes
                if (eb != null) {
                    for (i in 0 until size) if (cur[curOff + i] != eb[i]) return false
                    true
                } else {
                    val f = floatArg!!
                    ValueCodec.roundedEquals(ValueCodec.decodeDouble(type, cur, curOff), f.value, f.decimals)
                }
            }
            ScanMode.BIGGER_THAN -> if (type.isFloat) ValueCodec.decodeDouble(type, cur, curOff) > lowF
                else ValueCodec.decodeLong(type, cur, curOff) > lowInt
            ScanMode.SMALLER_THAN -> if (type.isFloat) ValueCodec.decodeDouble(type, cur, curOff) < lowF
                else ValueCodec.decodeLong(type, cur, curOff) < lowInt
            ScanMode.BETWEEN -> if (type.isFloat) ValueCodec.decodeDouble(type, cur, curOff).let { it >= lowF && it <= highF }
                else ValueCodec.decodeLong(type, cur, curOff).let { it >= lowInt && it <= highInt }
            ScanMode.CHANGED -> ValueCodec.rawBits(type, cur, curOff) != ValueCodec.rawBits(type, prev!!, prevOff)
            ScanMode.UNCHANGED -> ValueCodec.rawBits(type, cur, curOff) == ValueCodec.rawBits(type, prev!!, prevOff)
            ScanMode.INCREASED -> compare(cur, curOff, prev!!, prevOff) > 0
            ScanMode.DECREASED -> compare(cur, curOff, prev!!, prevOff) < 0
            ScanMode.INCREASED_BY -> deltaMatches(cur, curOff, prev!!, prevOff, +1)
            ScanMode.DECREASED_BY -> deltaMatches(cur, curOff, prev!!, prevOff, -1)
        }
    }

    /** -1/0/1; NaN compares as 0 so that it never counts as increased or decreased. */
    private fun compare(cur: ByteArray, co: Int, prev: ByteArray, po: Int): Int = if (type.isFloat) {
        val c = ValueCodec.decodeDouble(type, cur, co)
        val p = ValueCodec.decodeDouble(type, prev, po)
        if (c > p) 1 else if (c < p) -1 else 0
    } else {
        ValueCodec.decodeLong(type, cur, co).compareTo(ValueCodec.decodeLong(type, prev, po))
    }

    private fun deltaMatches(cur: ByteArray, co: Int, prev: ByteArray, po: Int, sign: Int): Boolean = if (type.isFloat) {
        val c = ValueCodec.decodeDouble(type, cur, co)
        val p = ValueCodec.decodeDouble(type, prev, po)
        val f = floatArg!!
        ValueCodec.roundedEquals((c - p) * sign, f.value, f.decimals)
    } else {
        val c = ValueCodec.decodeLong(type, cur, co)
        val p = ValueCodec.decodeLong(type, prev, po)
        // Wrap-around arithmetic at the type width (e.g. Int8 127 + 1 == -128 counts as increased by 1).
        val delta = if (sign > 0) c - p else p - c
        (delta and mask) == (intArg!! and mask)
    }
}
