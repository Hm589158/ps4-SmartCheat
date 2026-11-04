package com.ps4smartcheat.core.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuesTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun parsesSignedAndUnsignedIntegers() {
        assertEquals(-1L, (ValueCodec.parse(ValueType.INT8, "255") as ParsedValue.IntValue).value)
        assertEquals(-128L, (ValueCodec.parse(ValueType.INT8, "-128") as ParsedValue.IntValue).value)
        assertEquals(0x1234L, (ValueCodec.parse(ValueType.INT16, "0x1234") as ParsedValue.IntValue).value)
        assertEquals(-1L, (ValueCodec.parse(ValueType.INT64, "18446744073709551615") as ParsedValue.IntValue).value)
        assertEquals(5000L, (ValueCodec.parse(ValueType.INT32, " 5000 ") as ParsedValue.IntValue).value)
    }

    @Test fun rejectsOutOfRangeAndGarbage() {
        listOf(
            ValueType.INT8 to "256", ValueType.INT8 to "-129", ValueType.INT16 to "70000",
            ValueType.INT32 to "abc", ValueType.INT32 to "", ValueType.INT32 to "1.5",
            ValueType.FLOAT32 to "1e39", ValueType.FLOAT64 to "NaN", ValueType.FLOAT64 to "Infinity",
            ValueType.FLOAT32 to "0x10",
        ).forEach { (t, s) ->
            try {
                ValueCodec.parse(t, s); throw AssertionError("accepted $t '$s'")
            } catch (e: ValueParseException) { /* ok */ }
        }
    }

    @Test fun littleEndianEncoding() {
        assertArrayEquals(bytes(0x88, 0x13, 0, 0), ValueCodec.encode(ValueCodec.parse(ValueType.INT32, "5000")))
        assertArrayEquals(bytes(0xFF, 0xFF), ValueCodec.encode(ValueCodec.parse(ValueType.INT16, "-1")))
        assertArrayEquals(bytes(0, 0, 0x80, 0x3F), ValueCodec.encode(ValueCodec.parse(ValueType.FLOAT32, "1.0")))
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0xF0, 0x3F), ValueCodec.encode(ValueCodec.parse(ValueType.FLOAT64, "1")))
        assertArrayEquals(bytes(1, 0, 0, 0, 0, 0, 0, 0x80), ValueCodec.encodeLong(ValueType.INT64, Long.MIN_VALUE + 1))
    }

    @Test fun decodeRoundTripsAllTypes() {
        for (t in ValueType.entries) {
            val v = ValueCodec.parse(t, if (t.isFloat) "-12.25" else "-100")
            val b = ValueCodec.encode(v)
            assertEquals(t.size, b.size)
            if (t.isFloat) assertEquals(-12.25, ValueCodec.decodeDouble(t, b, 0), 0.0)
            else assertEquals(-100L, ValueCodec.decodeLong(t, b, 0))
        }
        assertEquals("-100", ValueCodec.format(ValueType.INT8, bytes(0x9C)))
    }

    @Test fun floatDecimalsDriveRoundedMatching() {
        val v = ValueCodec.parse(ValueType.FLOAT32, "100.5") as ParsedValue.FloatValue
        assertEquals(1, v.decimals)
        assertTrue(ValueCodec.roundedEquals(100.54, 100.5, 1))
        assertFalse(ValueCodec.roundedEquals(100.56, 100.5, 1))
        assertTrue(ValueCodec.roundedEquals(99.6, 100.0, 0))
        assertFalse(ValueCodec.roundedEquals(Double.NaN, 100.0, 0))
    }

    @Test fun matcherModesForIntegers() {
        val t = ValueType.INT32
        fun enc(v: Long) = ValueCodec.encodeLong(t, v)
        fun m(mode: ScanMode, arg: String? = null) = Matcher(t, mode, arg?.let { ValueCodec.parse(t, it) })
        assertTrue(m(ScanMode.EXACT, "5000").matches(enc(5000), 0, null, 0))
        assertFalse(m(ScanMode.EXACT, "5000").matches(enc(5001), 0, null, 0))
        assertTrue(m(ScanMode.DECREASED).matches(enc(4200), 0, enc(5000), 0))
        assertFalse(m(ScanMode.INCREASED).matches(enc(4200), 0, enc(5000), 0))
        assertTrue(m(ScanMode.INCREASED).matches(enc(1), 0, enc(-1), 0)) // signed compare
        assertTrue(m(ScanMode.CHANGED).matches(enc(1), 0, enc(2), 0))
        assertTrue(m(ScanMode.UNCHANGED).matches(enc(2), 0, enc(2), 0))
        assertTrue(m(ScanMode.DECREASED_BY, "800").matches(enc(4200), 0, enc(5000), 0))
        assertTrue(m(ScanMode.INCREASED_BY, "1").matches(enc(Int.MIN_VALUE.toLong()), 0, enc(Int.MAX_VALUE.toLong()), 0)) // wrap
    }

    @Test fun matcherModesForFloats() {
        val t = ValueType.FLOAT32
        fun enc(v: Double) = ValueCodec.encodeDouble(t, v)
        val nan = ValueCodec.encodeLong(ValueType.INT32, 0x7FC00000)
        assertTrue(Matcher(t, ScanMode.EXACT, ValueCodec.parse(t, "3.14")).matches(enc(3.14159), 0, null, 0))
        assertTrue(Matcher(t, ScanMode.DECREASED, null).matches(enc(1.5), 0, enc(2.0), 0))
        assertFalse(Matcher(t, ScanMode.DECREASED, null).matches(nan, 0, enc(2.0), 0))
        assertFalse(Matcher(t, ScanMode.INCREASED, null).matches(nan, 0, enc(2.0), 0))
        assertTrue(Matcher(t, ScanMode.UNCHANGED, null).matches(nan, 0, nan, 0))
        assertTrue(Matcher(t, ScanMode.INCREASED_BY, ValueCodec.parse(t, "0.5")).matches(enc(2.5), 0, enc(2.0), 0))
    }

    @Test fun alignmentHelpers() {
        assertEquals(8L, ScanEngine.alignUp(5, 4))
        assertEquals(8L, ScanEngine.alignUp(8, 4))
        assertEquals(4L, ScanEngine.alignedCount(0, 16, 4, 4))
        assertEquals(13L, ScanEngine.alignedCount(0, 16, 4, 1))
        assertEquals(0L, ScanEngine.alignedCount(0, 3, 4, 1))
    }
}
