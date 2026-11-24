package com.ps4smartcheat.core.scan

import kotlin.math.abs
import kotlin.math.pow

/**
 * The Float/Double values this app's [Matcher] accepts, as one inclusive interval of values representable in the type.
 *
 * ps4debug-NG compares "value between" inclusively in the type's own precision (`proc.c` proc_scan_compareValues,
 * `cmpTypeValueBetween`), and Turbo Scan runs it for floats (`scan_turbo.c` turboscan_policy_compare). Sending the
 * smallest and the largest accepted value therefore makes the PS4 find exactly the addresses the Android compare finds:
 * the accepted set is an interval (the matcher's tests are monotonic in the value), NaN never matches on either side,
 * and ±Infinity match only when the interval reaches them.
 */
object FloatRange {
    /** Inclusive bounds, both representable in the type (a Float bound is exact as a Double). */
    data class Bounds(val low: Double, val high: Double)

    /** Steps the search may take from a seed to the edge of the set (seeds are within a few ulps). */
    private const val MAX_STEPS = 256

    /** Values that "round" to [v] at the decimals typed ([ValueCodec.roundedEquals]); null when no value does. */
    fun exact(type: ValueType, v: ParsedValue.FloatValue): Bounds? {
        val tol = 0.5 * 10.0.pow(-v.decimals) + 1e-12 * maxOf(1.0, abs(v.value))
        return interval(type, v.value - tol, v.value + tol) { ValueCodec.roundedEquals(it, v.value, v.decimals) }
    }

    /** Values in [low, high] (the matcher's "between"); null when none is representable. */
    fun between(type: ValueType, low: Double, high: Double): Bounds? =
        interval(type, low, high) { it >= low && it <= high }

    /** Little-endian low ‖ high in the type's encoding: the value data of an NG "between" compare. */
    fun encode(type: ValueType, b: Bounds): ByteArray =
        ValueCodec.encodeDouble(type, b.low) + ValueCodec.encodeDouble(type, b.high)

    private fun interval(type: ValueType, seedLow: Double, seedHigh: Double, accepts: (Double) -> Boolean): Bounds? {
        require(type.isFloat) { "$type is not a floating type" }
        if (seedLow.isNaN() || seedHigh.isNaN() || seedLow > seedHigh) return null
        val f32 = type == ValueType.FLOAT32
        fun fit(x: Double) = if (f32) x.toFloat().toDouble() else x
        fun up(x: Double) = if (f32) Math.nextUp(x.toFloat()).toDouble() else Math.nextUp(x)
        fun down(x: Double) = if (f32) Math.nextDown(x.toFloat()).toDouble() else Math.nextDown(x)
        fun edge(seed: Double, inward: (Double) -> Double, outward: (Double) -> Double): Double? {
            var x = fit(seed)
            var steps = 0
            while (!accepts(x)) { x = inward(x); if (++steps > MAX_STEPS || x.isNaN()) return null }
            while (true) {
                val next = outward(x)
                if (next == x || next.isNaN() || !accepts(next)) return x
                x = next
                if (++steps > MAX_STEPS) return null
            }
        }
        val low = edge(seedLow, ::up, ::down) ?: return null
        val high = edge(seedHigh, ::down, ::up) ?: return null
        return if (low <= high) Bounds(low, high) else null
    }
}
