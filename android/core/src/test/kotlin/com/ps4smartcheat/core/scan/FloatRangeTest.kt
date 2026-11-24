package com.ps4smartcheat.core.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The bounds sent to the PS4 for a float scan must accept exactly the values the Android matcher accepts: every
 * value between them (inclusive, in the type's precision) and none outside.
 */
class FloatRangeTest {
    private fun up(type: ValueType, x: Double) = if (type == ValueType.FLOAT32) Math.nextUp(x.toFloat()).toDouble() else Math.nextUp(x)
    private fun down(type: ValueType, x: Double) = if (type == ValueType.FLOAT32) Math.nextDown(x.toFloat()).toDouble() else Math.nextDown(x)

    /** The PS4 side of the compare: inclusive between in the type's precision (NaN never matches). */
    private fun server(b: FloatRange.Bounds, x: Double) = x >= b.low && x <= b.high

    private fun checkAround(type: ValueType, b: FloatRange.Bounds, accepts: (Double) -> Boolean, what: String) {
        assertTrue("$what low", accepts(b.low)); assertTrue("$what high", accepts(b.high))
        for (edge in listOf(b.low, b.high)) {
            var x = edge; repeat(40) { x = down(type, x) }
            repeat(80) {
                assertEquals("$what at $x", accepts(x), server(b, x))
                x = up(type, x)
            }
        }
    }

    @Test fun exactMatchesTheRoundedCompareAtItsEdges() {
        val rnd = Random(7)
        val texts = listOf("12.5", "100", "0", "-3.25", "0.1", "1e6", "99.99", "-0.001", "3.14159", "65535", "1.5e-5") +
            List(150) { "%.${rnd.nextInt(0, 6)}f".format(java.util.Locale.ROOT, (rnd.nextDouble() - 0.5) * 10.0.pow(rnd.nextInt(-3, 8))) }
        for (type in listOf(ValueType.FLOAT32, ValueType.FLOAT64)) for (t in texts) {
            val v = ValueCodec.parse(type, t) as ParsedValue.FloatValue
            val accepts = { x: Double -> ValueCodec.roundedEquals(x, v.value, v.decimals) }
            val b = FloatRange.exact(type, v)
            if (b == null) {
                // More decimals than the type holds at this size: no value of the type rounds to it (Android finds none).
                var x = if (type == ValueType.FLOAT32) v.value.toFloat().toDouble() else v.value
                repeat(3) { x = down(type, x) }
                repeat(7) { assertFalse("$type \"$t\" accepts $x", accepts(x)); x = up(type, x) }
                continue
            }
            checkAround(type, b, accepts, "$type \"$t\"")
            // Bounds are representable in the type (a Float bound survives the round trip).
            if (type == ValueType.FLOAT32) assertEquals(b.low, b.low.toFloat().toDouble(), 0.0)
        }
    }

    @Test fun randomValuesAgree() {
        val rnd = Random(11)
        val v = ValueCodec.parse(ValueType.FLOAT32, "250.5") as ParsedValue.FloatValue
        val b = FloatRange.exact(ValueType.FLOAT32, v)!!
        repeat(200_000) {
            val x = java.lang.Float.intBitsToFloat(rnd.nextInt()).toDouble()
            assertEquals("$x", ValueCodec.roundedEquals(x, v.value, v.decimals), server(b, x))
        }
        val near = List(100_000) { 250.0 + rnd.nextDouble() }
        for (d in near) { val x = d.toFloat().toDouble(); assertEquals("$x", ValueCodec.roundedEquals(x, v.value, v.decimals), server(b, x)) }
    }

    @Test fun betweenIsInclusiveAndUnrepresentableRangesAreRefused() {
        for (type in listOf(ValueType.FLOAT32, ValueType.FLOAT64)) {
            val b = FloatRange.between(type, 0.1, 0.3)!!
            checkAround(type, b, { it >= 0.1 && it <= 0.3 }, "$type between")
            val whole = FloatRange.between(type, -1.0, 1.0)!!
            assertTrue(server(whole, -0.0) && server(whole, 0.0))
            assertFalse(server(whole, Double.NaN))
        }
        // No Float lies strictly inside (1, nextUp(1)) — the PS4 cannot be asked for an empty set; Android answers it.
        val one = 1.0f.toDouble()
        assertNull(FloatRange.between(ValueType.FLOAT32, one + 1e-9, one + 2e-9))
        // 15 typed decimals around a value no Float is that close to.
        assertNull(FloatRange.exact(ValueType.FLOAT32, ValueCodec.parse(ValueType.FLOAT32, "0.100000000000000") as ParsedValue.FloatValue))
    }

    @Test fun encodingIsLowThenHighInTheTypesBytes() {
        val b = FloatRange.Bounds(1.5, 2.5)
        val f = FloatRange.encode(ValueType.FLOAT32, b)
        assertEquals(8, f.size)
        assertEquals(1.5, ValueCodec.decodeDouble(ValueType.FLOAT32, f, 0), 0.0)
        assertEquals(2.5, ValueCodec.decodeDouble(ValueType.FLOAT32, f, 4), 0.0)
        assertEquals(16, FloatRange.encode(ValueType.FLOAT64, b).size)
    }

    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())
}
