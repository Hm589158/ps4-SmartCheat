package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_R
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_W
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RangeModesTest {
    private fun m(type: ValueType, mode: ScanMode, a: String, b: String? = null) =
        Matcher(type, mode, ValueCodec.parse(type, a), b?.let { ValueCodec.parse(type, it) })

    private fun i32(v: Int) = ValueCodec.encodeLong(ValueType.INT32, v.toLong())
    private fun f32(v: Float) = ValueCodec.encodeDouble(ValueType.FLOAT32, v.toDouble())

    @Test fun integerBoundsAreSignedAndBetweenIsInclusiveInAnyOrder() {
        val between = m(ValueType.INT32, ScanMode.BETWEEN, "200", "100") // reversed bounds
        assertTrue(between.matches(i32(100), 0, null, 0))
        assertTrue(between.matches(i32(200), 0, null, 0))
        assertFalse(between.matches(i32(201), 0, null, 0))
        assertFalse(between.matches(i32(-150), 0, null, 0))
        assertTrue(m(ValueType.INT32, ScanMode.BIGGER_THAN, "-5").matches(i32(-4), 0, null, 0))
        assertFalse(m(ValueType.INT32, ScanMode.BIGGER_THAN, "-5").matches(i32(-5), 0, null, 0))
        assertTrue(m(ValueType.INT32, ScanMode.SMALLER_THAN, "0").matches(i32(-1), 0, null, 0)) // 0xFFFFFFFF is -1, not huge
        // Int8 "255" means -1 (same bit pattern), so "bigger than 255" means "bigger than -1".
        assertTrue(m(ValueType.INT8, ScanMode.BIGGER_THAN, "255").matches(byteArrayOf(0), 0, null, 0))
    }

    @Test fun floatBoundsAreExactAndRejectNaN() {
        val health = m(ValueType.FLOAT32, ScanMode.BETWEEN, "99", "100")
        assertTrue(health.matches(f32(99.5f), 0, null, 0))
        assertTrue(health.matches(f32(100f), 0, null, 0))
        assertFalse(health.matches(f32(100.01f), 0, null, 0))
        assertFalse(health.matches(f32(Float.NaN), 0, null, 0))
        assertFalse(m(ValueType.FLOAT32, ScanMode.BIGGER_THAN, "1").matches(f32(Float.NaN), 0, null, 0))
        assertTrue(m(ValueType.FLOAT64, ScanMode.SMALLER_THAN, "0.5").matches(ValueCodec.encodeDouble(ValueType.FLOAT64, 0.25), 0, null, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun betweenNeedsTwoValues() { Matcher(ValueType.INT32, ScanMode.BETWEEN, ValueCodec.parse(ValueType.INT32, "1")) }

    private val server = MockPs4DebugServer()
    private val client = Ps4DebugClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
        runBlocking { client.disconnect() }
        server.close()
    }

    @Test fun rangeScansMatchBruteForceAndFallBackFromServerTiers() = runBlocking {
        server.faults.enableNg(turbo = true)
        val p = server.addProcess(MockPs4DebugServer.Process(5, "eboot.bin", titleId = "CUSA00001"))
        val r = p.region("data", 0x10_0000, 0x4000, PROT_R or PROT_W)
        val buf = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 0x1000) buf.putInt(i * 4, (i * 37) % 1000 - 300)
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 5000))
        val controller = ScanController(client, scope, transport = client)
        controller.activate(5)
        controller.firstScan(5, "eboot.bin", ValueType.INT32, ScanMode.BETWEEN, "100", RegionFilter(), ScanOptions(), valueText2 = "150").join()
        val st = withTimeout(20_000) { controller.status.first { it !is ScanStatus.Running } }
        assertTrue(st.toString(), st is ScanStatus.Completed)
        val expected = (0 until 0x1000).filter { ((it * 37) % 1000 - 300) in 100..150 }.map { 0x10_0000L + it * 4 }
        val res = controller.results()!!
        assertEquals(expected, (0 until res.count).map { res.address(it) })
        assertEquals(ScanBackendKind.CLIENT, controller.lastStats.value!!.backend) // range modes run on Android
        assertTrue(controller.lastStats.value!!.fallbacks.isNotEmpty())
        // Next: values change, keep those now bigger than 140.
        for (a in expected) buf.putInt((a - 0x10_0000).toInt(), buf.getInt((a - 0x10_0000).toInt()) + 5)
        controller.nextScan(ScanMode.BIGGER_THAN, "140").join()
        withTimeout(20_000) { controller.status.first { it !is ScanStatus.Running } }
        val res2 = controller.results()!!
        val expected2 = expected.filter { buf.getInt((it - 0x10_0000).toInt()) > 140 }
        assertEquals(expected2, (0 until res2.count).map { res2.address(it) })
    }
}
