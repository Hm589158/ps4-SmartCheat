package com.ps4smartcheat.core.scan

enum class ScanMode(val label: String, val needsValue: Boolean, val needsPrevious: Boolean) {
    EXACT("Exact value", true, false),
    UNKNOWN_INITIAL("Unknown initial value", false, false),
    INCREASED("Increased", false, true),
    DECREASED("Decreased", false, true),
    CHANGED("Changed", false, true),
    UNCHANGED("Unchanged", false, true),
    INCREASED_BY("Increased by", true, true),
    DECREASED_BY("Decreased by", true, true);

    companion object {
        val FIRST_SCAN_MODES = listOf(EXACT, UNKNOWN_INITIAL)
        val NEXT_SCAN_MODES = listOf(EXACT, INCREASED, DECREASED, CHANGED, UNCHANGED, INCREASED_BY, DECREASED_BY)
    }
}

/**
 * Evaluates one candidate value. Integer comparisons (increased/decreased) are signed.
 * Float "unchanged"/"changed" compare raw bits so NaN payloads and -0.0 are handled deterministically.
 */
class Matcher(val type: ValueType, val mode: ScanMode, val value: ParsedValue?) {
    private val exactBytes: ByteArray? =
        if (mode == ScanMode.EXACT && value is ParsedValue.IntValue) ValueCodec.encode(value) else null
    private val intArg = (value as? ParsedValue.IntValue)?.value
    private val floatArg = value as? ParsedValue.FloatValue
    private val size = type.size
    private val mask: Long = if (size == 8) -1L else (1L shl (size * 8)) - 1

    init {
        require(!mode.needsValue || value != null) { "${mode.label} requires a value" }
        require(value == null || value.type == type) { "Value type mismatch" }
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
