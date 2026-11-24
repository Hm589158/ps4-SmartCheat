package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.NgProtocol
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_R
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_W
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Server-side scan tiers against the mock's ps4debug-NG emulation. Every tier must return exactly the
 * same addresses as the Android tier on the same memory.
 */
class ServerScanTest {
    private val servers = ArrayList<MockPs4DebugServer>()
    private val clients = ArrayList<Ps4DebugClient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pid = 900

    @After fun tearDown() {
        scope.cancel()
        runBlocking { clients.forEach { it.disconnect() } }
        servers.forEach { it.close() }
    }

    private class Rig(val server: MockPs4DebugServer, val client: Ps4DebugClient, val controller: ScanController)

    /** Deterministic dataset: 4 MiB rw heap with random bytes and planted values, 64 KiB read-only. */
    private fun rig(ng: Boolean = true, turbo: Boolean = true, configure: (MockPs4DebugServer.Faults) -> Unit = {}): Rig {
        val server = MockPs4DebugServer().also { servers += it }
        if (ng) server.faults.enableNg(turbo) else server.faults.closeOnUnknownCommand = true
        configure(server.faults)
        val p = server.addProcess(MockPs4DebugServer.Process(pid, "eboot.bin", titleId = "MOCK00001"))
        val heap = p.region("anon:heap", 0x1_0000_0000L, 4 shl 20, PROT_R or PROT_W)
        Random(42).nextBytes(heap.data)
        p.region("ro", 0x2_0000_0000L, 0x10000, PROT_R)
        val client = Ps4DebugClient(maxReadChunk = 256 * 1024).also { clients += it }
        runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 5000, firmwareProbeTimeoutMs = 300)) }
        return Rig(server, client, ScanController(client, scope))
    }

    private fun Rig.poke32(addr: Long, v: Int) = server.poke(pid, addr, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

    private suspend fun Rig.await(): ScanStatus = withTimeout(60_000) { controller.status.first { it !is ScanStatus.Running } }

    private fun Rig.addresses(): List<Long> = controller.results()!!.let { r -> (0 until r.count).map { r.address(it) } }

    private suspend fun Rig.first(type: ValueType, value: String, pref: BackendPreference, maxResults: Int = 1_000_000): ScanStatus {
        controller.firstScan(pid, "eboot.bin", type, ScanMode.EXACT, value, options = ScanOptions(backend = pref, maxResults = maxResults))
        return await()
    }

    private suspend fun Rig.next(mode: ScanMode, value: String? = null): ScanStatus {
        controller.nextScan(mode, value)
        return await()
    }

    @Test fun detectsNgCapabilities() {
        val r = rig()
        val caps = r.client.capabilities
        assertTrue(caps.isNg)
        assertTrue(caps.scanAuthorized)
        assertEquals(0x7FF, caps.turboEngines)
        assertTrue(caps.turboResident)
        assertEquals(listOf(ScanBackendKind.TURBO, ScanBackendKind.SERVER, ScanBackendKind.CLIENT), r.controller.availableBackends())
    }

    @Test fun classicServerUsesAndroidScan() = runBlocking {
        val r = rig(ng = false)
        assertFalse(r.client.capabilities.isNg)
        assertEquals(listOf(ScanBackendKind.CLIENT), r.controller.availableBackends())
        r.poke32(0x1_0000_0100L, 777777)
        assertTrue(r.first(ValueType.INT32, "777777", BackendPreference.AUTO) is ScanStatus.Completed)
        assertEquals(ScanBackendKind.CLIENT, r.controller.lastStats.value!!.backend)
        assertEquals(0, r.server.serverScanCommands.get())
        // Session still in sync after the probes closed the connection.
        assertEquals(1, r.client.getProcesses().size)
    }

    @Test fun failedAuthFallsBackToAndroid() = runBlocking {
        val r = rig { it.rejectAuth = true }
        assertTrue(r.client.capabilities.isNg)
        assertFalse(r.client.capabilities.scanAuthorized)
        r.poke32(0x1_0000_0100L, 777777)
        r.first(ValueType.INT32, "777777", BackendPreference.AUTO)
        val st = r.controller.lastStats.value!!
        assertEquals(ScanBackendKind.CLIENT, st.backend)
        assertTrue(st.fallbacks.toString(), st.fallbacks.any { it.contains("authorisation") })
    }

    @Test fun authKeystreamMatchesMock() {
        val ch = ByteArray(64) { it.toByte() }
        assertTrue(NgProtocol.authResponse(ch).contentEquals(MockPs4DebugServer.xorKeystream(ch)))
    }

    @Test fun currencyScenarioOnTurbo() = runBlocking {
        val r = rig()
        val coins = 0x1_0000_0120L
        val decoy = 0x1_0020_0000L
        r.poke32(coins, 5000); r.poke32(decoy, 5000)
        r.first(ValueType.INT32, "5000", BackendPreference.AUTO)
        assertEquals(ScanBackendKind.TURBO, r.controller.lastStats.value!!.backend)
        assertTrue(r.addresses().containsAll(listOf(coins, decoy)))
        val readsAfterFirst = r.server.readRequests.get()
        r.poke32(coins, 4200); r.poke32(decoy, 4200)
        r.next(ScanMode.EXACT, "4200")
        assertEquals(ScanBackendKind.TURBO, r.controller.lastStats.value!!.backend)
        r.poke32(coins, 3900); r.poke32(decoy, 4500)
        r.next(ScanMode.DECREASED)
        assertEquals(listOf(coins), r.addresses())
        assertEquals(ScanBackendKind.TURBO, r.controller.session.value!!.backend)
        assertEquals(readsAfterFirst, r.server.readRequests.get()) // no CMD_PROC_READ used for scanning
        assertEquals(0, readsAfterFirst)
    }

    /** All three tiers must agree on every integer type and next-scan mode. */
    @Test fun tiersProduceIdenticalResults() = runBlocking {
        val tiers = listOf(BackendPreference.CLIENT, BackendPreference.SERVER, BackendPreference.TURBO)
        for (type in listOf(ValueType.INT8, ValueType.INT16, ValueType.INT32, ValueType.INT64)) {
            val outcomes = tiers.map { pref ->
                val r = rig()
                val value = if (type == ValueType.INT8) "7" else "123"
                // Plant extra matches; then mutate some to exercise each refine mode.
                val enc = ValueCodec.encode(ValueCodec.parse(type, value))
                val planted = (0 until 40).map { 0x1_0000_0000L + 4096L * it + type.size * 3 }
                planted.forEach { r.server.poke(pid, it, enc) }
                r.first(type, value, pref)
                val seq = ArrayList<List<Long>>()
                seq += r.addresses()
                val backends = ArrayList<ScanBackendKind>()
                backends += r.controller.lastStats.value!!.backend
                fun set(i: Int, v: Long) = r.server.poke(pid, planted[i], ValueCodec.encodeLong(type, v))
                for (i in 0 until 10) set(i, 200)          // increased
                for (i in 10 until 20) set(i, -5)          // decreased
                r.next(ScanMode.CHANGED); seq += r.addresses(); backends += r.controller.lastStats.value!!.backend
                r.next(ScanMode.UNCHANGED); seq += r.addresses(); backends += r.controller.lastStats.value!!.backend
                for (i in 0 until 5) set(i, 300)
                for (i in 10 until 15) set(i, -9)
                r.next(ScanMode.INCREASED); seq += r.addresses(); backends += r.controller.lastStats.value!!.backend
                seq to backends
            }
            val expected = outcomes[0].first
            assertTrue(outcomes[0].second.all { it == ScanBackendKind.CLIENT })
            assertTrue("server tier used ${outcomes[1].second}", outcomes[1].second.all { it == ScanBackendKind.SERVER })
            assertTrue("turbo tier used ${outcomes[2].second}", outcomes[2].second.all { it == ScanBackendKind.TURBO })
            assertEquals("SERVER vs CLIENT for $type", expected, outcomes[1].first)
            assertEquals("TURBO vs CLIENT for $type", expected, outcomes[2].first)
            assertEquals(5, expected.last().size) // the 5 values raised to 300 (others unchanged since last scan)
        }
    }

    @Test fun residentOverflowFallsBackToIterative() = runBlocking {
        val r = rig { it.turboResidentCapBytes = 1024 }
        r.server.poke(pid, 0x1_0010_0000L, ByteArray(8192)) // 2048 zero Int32 values > 85 that fit the cap
        val status = r.first(ValueType.INT32, "0", BackendPreference.AUTO)
        assertTrue(status.toString(), status is ScanStatus.Completed)
        val st = r.controller.lastStats.value!!
        assertEquals(ScanBackendKind.SERVER, st.backend)
        assertTrue(st.fallbacks.toString(), st.fallbacks.any { it.contains("buffer full") })
        assertTrue(r.controller.results()!!.count >= 2048)
    }

    /**
     * Float and Double scans run on Turbo Scan and find exactly what the Android compare finds: rounded exact values,
     * between, increased / decreased (NaN never counts), changed / unchanged on the raw bits (0.0 → -0.0 is a change).
     */
    @Test fun floatsRunOnTurboWithTheSameResultsAsAndroid() = runBlocking {
        for (type in listOf(ValueType.FLOAT32, ValueType.FLOAT64)) {
            val outcomes = listOf(BackendPreference.CLIENT, BackendPreference.TURBO).map { pref ->
                val r = rig()
                val planted = (0 until 40).map { 0x1_0000_0000L + 4096L * it + type.size * 3 }
                fun set(i: Int, v: Double) = r.server.poke(pid, planted[i], ValueCodec.encodeDouble(type, v))
                // 12.5 typed with one decimal matches 12.45 ≤ x < 12.55: plant inside, on and past the edges.
                for (i in planted.indices) set(i, listOf(12.5, 12.46, 12.549, 12.45, 12.55, 12.6, 12.4)[i % 7])
                val seq = ArrayList<List<Long>>()
                val backends = ArrayList<ScanBackendKind>()
                fun record() { seq += r.addresses(); backends += r.controller.lastStats.value!!.backend }
                r.first(type, "12.5", pref); record()
                for (i in 0 until 6) set(i, 13.0)
                for (i in 7 until 12) set(i, 12.0)
                set(14, Double.NaN)
                r.next(ScanMode.CHANGED); record()
                r.next(ScanMode.UNCHANGED); record()
                set(0, 14.0); set(7, 11.0); set(14, 20.0)
                r.next(ScanMode.INCREASED); record()
                r.next(ScanMode.EXACT, "14"); record()
                // Between as a first scan, then a sign-only change of zero.
                for (i in 20 until 25) set(i, 0.0)
                r.controller.firstScan(pid, "eboot.bin", type, ScanMode.BETWEEN, "-0.5", valueText2 = "0.25", options = ScanOptions(backend = pref, turboFetchMax = 1_000_000))
                r.await(); record()
                set(20, -0.0)
                r.next(ScanMode.CHANGED); record()
                seq to backends
            }
            assertTrue(outcomes[0].second.toString(), outcomes[0].second.all { it == ScanBackendKind.CLIENT })
            assertTrue("$type turbo tier used ${outcomes[1].second}", outcomes[1].second.all { it == ScanBackendKind.TURBO })
            assertEquals("TURBO vs CLIENT for $type", outcomes[0].first, outcomes[1].first)
            val first = outcomes[0].first[0]
            assertTrue(first.toString(), first.size >= 18) // 12.5, 12.46, 12.549, 12.45 (×4 each, plus 12.5 at index 35+)
            assertEquals(listOf(0x1_0000_0000L + type.size * 3), outcomes[0].first[4]) // only the value raised to 14
            assertEquals(listOf(0x1_0000_0000L + 4096L * 20 + type.size * 3), outcomes[0].first[6]) // 0.0 → -0.0
        }
    }

    @Test fun floatsWithoutTurboAndUnalignedStayOnAndroid() = runBlocking {
        val r = rig()
        r.server.poke(pid, 0x1_0000_0040L, ValueCodec.encodeDouble(ValueType.FLOAT32, 12.5))
        r.first(ValueType.FLOAT32, "12.5", BackendPreference.SERVER)
        assertEquals(ScanBackendKind.CLIENT, r.controller.lastStats.value!!.backend)
        assertTrue(r.addresses().contains(0x1_0000_0040L))
        r.controller.firstScan(pid, "eboot.bin", ValueType.FLOAT32, ScanMode.EXACT, "12.5", options = ScanOptions(alignment = 1))
        r.await()
        assertEquals(ScanBackendKind.CLIENT, r.controller.lastStats.value!!.backend)
        r.controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5", options = ScanOptions(alignment = 1))
        r.await()
        assertEquals(ScanBackendKind.CLIENT, r.controller.lastStats.value!!.backend)
        // A float refine NG has no equal compare for (rounded "increased by") runs on Android from the Turbo results.
        r.first(ValueType.FLOAT32, "12.5", BackendPreference.AUTO)
        assertEquals(ScanBackendKind.TURBO, r.controller.lastStats.value!!.backend)
        r.server.poke(pid, 0x1_0000_0040L, ValueCodec.encodeDouble(ValueType.FLOAT32, 13.5))
        r.next(ScanMode.INCREASED_BY, "1")
        assertEquals(ScanBackendKind.CLIENT, r.controller.lastStats.value!!.backend)
        assertEquals(listOf(0x1_0000_0040L), r.addresses())
    }

    @Test fun serverKeepsFullSetWhenLocalListIsCapped() = runBlocking {
        val r = rig()
        // Plenty of zero Int16 values exist in random data plus a zeroed page.
        r.server.poke(pid, 0x1_0010_0000L, ByteArray(8192))
        r.first(ValueType.INT16, "0", BackendPreference.AUTO, maxResults = 1000)
        val res = r.controller.results()!!
        assertEquals(1000, res.count)
        assertTrue(res.serverTotal!! > 1000)
        assertTrue(res.truncated)
        // Change one zero far beyond the first 1000 locally kept results; Turbo still finds it.
        val target = 0x1_0010_0000L + 4000
        r.server.poke(pid, target, byteArrayOf(9, 0))
        r.next(ScanMode.CHANGED)
        assertEquals(listOf(target), r.addresses())
        assertEquals(ScanBackendKind.TURBO, r.controller.lastStats.value!!.backend)
    }

    @Test fun turboFetchIsBoundedButServerKeepsAll() = runBlocking {
        val r = rig()
        r.server.poke(pid, 0x1_0010_0000L, ByteArray(8192))
        r.controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "0", options = ScanOptions(turboFetchMax = 100))
        r.await()
        val res = r.controller.results()!!
        assertEquals(100, res.count)
        assertTrue(res.serverTotal!! >= 2048)
        val st = r.controller.lastStats.value!!
        // Fetch traffic is bounded: 100 records × (8 + 2×4) bytes plus framing, not 2048 records.
        assertTrue(st.traffic.bytesIn.toString(), st.traffic.bytesIn < 100 * 16 + 512)
    }

    @Test fun lostTurboSessionFallsBackWithLocalResults() = runBlocking {
        val r = rig()
        r.poke32(0x1_0000_0120L, 5000)
        r.first(ValueType.INT32, "5000", BackendPreference.AUTO)
        assertEquals(ScanBackendKind.TURBO, r.controller.lastStats.value!!.backend)
        // A failing command makes the server close the session; the client reconnects (new epoch).
        runCatching { r.client.getMemoryRegions(424242) }
        r.poke32(0x1_0000_0120L, 4999)
        r.next(ScanMode.DECREASED)
        val st = r.controller.lastStats.value!!
        assertTrue(st.fallbacks.toString(), st.fallbacks.any { it.contains("lost") })
        assertTrue(st.backend != ScanBackendKind.TURBO)
        assertEquals(listOf(0x1_0000_0120L), r.addresses())
    }

    @Test fun turboCancelUsesSideChannel() = runBlocking {
        val r = rig { it.scanDelayMsPerMiB = 400 }
        r.controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "1", options = ScanOptions())
        delay(300)
        val t0 = System.currentTimeMillis()
        r.controller.cancel()
        assertEquals(ScanStatus.Cancelled, r.await())
        assertTrue("cancel took too long", System.currentTimeMillis() - t0 < 1500)
        assertNull(r.controller.results())
        // Connection is still usable.
        r.server.faults.scanDelayMsPerMiB = 0
        r.poke32(0x1_0000_0120L, 31337)
        r.first(ValueType.INT32, "31337", BackendPreference.AUTO)
        assertTrue(r.addresses().contains(0x1_0000_0120L))
    }

    @Test fun iterativeServerRefineIsUsedWhenCheaper() = runBlocking {
        val r = rig(turbo = false)
        // Sparse candidates: one per 64 KiB -> Android would read a span per candidate.
        val planted = (0 until 60).map { 0x1_0000_0000L + 65536L * it }
        planted.forEach { r.poke32(it, 424242) }
        r.first(ValueType.INT32, "424242", BackendPreference.AUTO)
        assertEquals(ScanBackendKind.SERVER, r.controller.lastStats.value!!.backend)
        r.poke32(planted[3], 1)
        r.next(ScanMode.CHANGED)
        val st = r.controller.lastStats.value!!
        assertEquals(ScanBackendKind.SERVER, st.backend)
        assertEquals(listOf(planted[3]), r.addresses())
        assertNotNull(st.traffic)
    }

    @Test fun turboTransfersFarLessThanAndroid() = runBlocking {
        val android = rig()
        android.first(ValueType.INT32, "123456", BackendPreference.CLIENT)
        val a = android.controller.lastStats.value!!
        val turbo = rig()
        turbo.first(ValueType.INT32, "123456", BackendPreference.TURBO)
        val t = turbo.controller.lastStats.value!!
        assertEquals(ScanBackendKind.TURBO, t.backend)
        assertTrue("android ${a.traffic} turbo ${t.traffic}", t.traffic.total * 100 < a.traffic.total)
        assertTrue(a.traffic.bytesIn >= 4L shl 20) // full region transferred
    }
}
