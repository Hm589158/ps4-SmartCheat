package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_R
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_W
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_X
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scanner tests against the mock ps4debug server over real TCP. Expected match counts are computed by
 * brute force from the mock's memory contents, never hardcoded.
 */
class ScannerTest {
    private lateinit var server: MockPs4DebugServer
    private lateinit var client: Ps4DebugClient
    private lateinit var scope: CoroutineScope
    private lateinit var controller: ScanController
    private val pid = DemoGame.PID

    @Before fun setUp() {
        server = MockPs4DebugServer()
        DemoGame.install(server)
        client = Ps4DebugClient(maxReadChunk = 256 * 1024)
        runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 5000)) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = ScanController(client, scope)
    }

    @After fun tearDown() {
        scope.cancel()
        runBlocking { client.disconnect() }
        server.close()
    }

    /** Brute-force reference: all aligned addresses in rw, non-exec regions whose bytes equal [needle]. */
    private fun bruteForce(needle: ByteArray, align: Int = needle.size): List<Long> {
        val out = ArrayList<Long>()
        for (r in server.process(pid)!!.regions.sortedBy { it.start }) {
            if (r.prot and PROT_W == 0 || r.prot and PROT_X != 0) continue
            var a = ScanEngine.alignUp(r.start, align)
            while (a + needle.size <= r.end) {
                val off = (a - r.start).toInt()
                if ((needle.indices).all { r.data[off + it] == needle[it] }) out += a
                a += align
            }
        }
        return out
    }

    private fun int32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private suspend fun awaitDone(): ScanStatus = withTimeout(30_000) {
        controller.status.first { it !is ScanStatus.Running }
    }

    private fun addresses(): List<Long> {
        val r = controller.results()!!
        return (0 until r.count).map { r.address(it) }
    }

    @Test fun currencyScenario5000to4200to3900() = runBlocking {
        // Decoys: one unchanged 5000, one that also drops to 4200 but then rises.
        val decoyStatic = DemoGame.HEAP_START + 0x1000
        val decoyFollower = DemoGame.HEAP_START + 0x2000
        server.pokeInt32(pid, decoyStatic, 5000)
        server.pokeInt32(pid, decoyFollower, 5000)

        val expected1 = bruteForce(int32(5000))
        assertTrue(expected1.size >= 3)
        controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000")
        assertTrue(awaitDone() is ScanStatus.Completed)
        assertEquals(expected1, addresses())

        // In-game: spend 800.
        server.pokeInt32(pid, DemoGame.COINS_ADDRESS, 4200)
        server.pokeInt32(pid, decoyFollower, 4200)
        controller.nextScan(ScanMode.EXACT, "4200")
        awaitDone()
        val expected2 = expected1.filter { server.peekInt32(pid, it) == 4200 }
        assertEquals(expected2, addresses())
        assertEquals(listOf(DemoGame.COINS_ADDRESS, decoyFollower), expected2)

        // In-game: spend 300 more; the decoy goes elsewhere.
        server.pokeInt32(pid, DemoGame.COINS_ADDRESS, 3900)
        server.pokeInt32(pid, decoyFollower, 4500)
        controller.nextScan(ScanMode.DECREASED, null)
        awaitDone()
        assertEquals(listOf(DemoGame.COINS_ADDRESS), addresses())

        // Candidate can be inspected with a fresh read.
        val row = controller.readPage(0, 50).single()
        assertEquals(DemoGame.COINS_ADDRESS, row.address)
        assertEquals(3900, ByteBuffer.wrap(row.current!!).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(3, controller.session.value!!.step)
    }

    @Test fun firstScanMatchesBruteForceForEveryType() = runBlocking {
        for (t in ValueType.entries) {
            // Plant one value per type and also count accidental matches in random data.
            val text = if (t.isFloat) "1234.5" else "-77"
            val bytes = ValueCodec.encode(ValueCodec.parse(t, text))
            server.poke(pid, DemoGame.HEAP_START + 0x4000 + t.ordinal * 16, bytes)
            controller.firstScan(pid, "eboot.bin", t, ScanMode.EXACT, text)
            awaitDone()
            val got = addresses()
            if (!t.isFloat) {
                assertEquals("type $t", bruteForce(bytes), got)
            } else {
                // Rounded float matching may also accept nearby values; the exact plant must be there.
                assertTrue(got.containsAll(bruteForce(bytes)))
                got.forEach {
                    val v = ValueCodec.decodeDouble(t, server.peek(pid, it, t.size), 0)
                    assertTrue(ValueCodec.roundedEquals(v, 1234.5, 1))
                }
            }
        }
    }

    @Test fun regionBoundariesAndChunkOverlap() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(500, "bounds.elf"))
        val a = p.region("a", 0x10_0000, 4096, PROT_R or PROT_W)
        val b = p.region("b", 0x10_1000, 4096, PROT_R or PROT_W) // adjacent to a
        val needle = int32(0x0BADF00D)
        // Value at the very start of region a and the very end of region b.
        needle.copyInto(a.data, 0)
        needle.copyInto(b.data, 4092)
        // Value straddling a->b must NOT be found (would require reading across regions).
        needle.copyInto(a.data, 4094, 0, 2); needle.copyInto(b.data, 0, 2, 4)
        // Unaligned value straddling an internal 16-byte chunk boundary in b (found only with alignment 1).
        needle.copyInto(b.data, 30)
        val opts = ScanOptions(chunkSize = 16, alignment = 1)
        controller.firstScan(500, "bounds.elf", ValueType.INT32, ScanMode.EXACT, "0x0BADF00D", options = opts)
        awaitDone()
        assertEquals(listOf(0x10_0000L, 0x10_101EL, 0x10_1FFCL), addresses())

        // With natural alignment the unaligned one disappears.
        controller.firstScan(500, "bounds.elf", ValueType.INT32, ScanMode.EXACT, "0x0BADF00D", options = ScanOptions(chunkSize = 16))
        awaitDone()
        assertEquals(listOf(0x10_0000L, 0x10_1FFCL), addresses())
    }

    @Test fun readsNeverLeaveSelectedRegions() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(501, "ro.elf"))
        p.region("ro", 0x20_0000, 8192, PROT_R)
        p.region("rw", 0x30_0000, 8192, PROT_R or PROT_W)
        p.region("rx", 0x40_0000, 8192, PROT_R or PROT_X)
        val before = server.readRequests.get()
        controller.firstScan(501, "ro.elf", ValueType.INT32, ScanMode.EXACT, "0", options = ScanOptions(chunkSize = 4096))
        awaitDone()
        assertEquals(2, server.readRequests.get() - before) // only the rw region, two chunks
        assertTrue(addresses().all { it in 0x30_0000L until 0x30_2000L })
        assertEquals(2048, addresses().size) // all zero
    }

    @Test fun resultCapIsEnforced() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(502, "zeros.elf"))
        p.region("z", 0x50_0000, 1 shl 20, PROT_R or PROT_W)
        controller.firstScan(502, "zeros.elf", ValueType.INT32, ScanMode.EXACT, "0", options = ScanOptions(maxResults = 1000))
        val st = awaitDone() as ScanStatus.Completed
        val r = controller.results()!!
        assertEquals(1000, r.count)
        assertTrue(r.truncated)
        assertTrue(st.message.contains("cap"))
    }

    @Test fun largeRegionIsStreamedInChunks() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(503, "big.elf"))
        val size = 48 shl 20
        val r = p.region("big", 0x1_0000_0000L, size, PROT_R or PROT_W)
        java.util.Arrays.fill(r.data, 0x11)
        int32(424242).copyInto(r.data, size - 4)
        val before = server.readRequests.get()
        controller.firstScan(503, "big.elf", ValueType.INT32, ScanMode.EXACT, "424242", options = ScanOptions(chunkSize = 1 shl 20))
        awaitDone()
        assertEquals(listOf(0x1_0000_0000L + size - 4), addresses())
        // 48 chunks of 1 MiB, each split by the client into 256 KiB CMD_PROC_READ requests.
        assertEquals(48 * 4, server.readRequests.get() - before)
    }

    @Test fun cancellationKeepsPreviousResults() = runBlocking {
        controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000")
        awaitDone()
        val before = addresses()
        server.faults.readDelayMs = 200
        controller.nextScan(ScanMode.UNCHANGED, null)
        delay(50)
        controller.cancel()
        assertEquals(ScanStatus.Cancelled, awaitDone())
        assertEquals(before, addresses())
        server.faults.readDelayMs = 0
        // Connection still usable afterwards (the in-flight request completes before cancellation lands).
        controller.nextScan(ScanMode.UNCHANGED, null)
        awaitDone()
        assertEquals(before, addresses())
    }

    @Test fun firstScanCancellation() = runBlocking {
        server.faults.readDelayMs = 100
        controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "1", options = ScanOptions(chunkSize = 64 * 1024))
        delay(150)
        controller.cancel()
        assertEquals(ScanStatus.Cancelled, awaitDone())
        assertEquals(null, controller.results())
    }

    @Test fun disconnectDuringScanRecoversAndCompletes() = runBlocking {
        controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000")
        awaitDone()
        val before = addresses()
        server.faults.readDelayMs = 100
        controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000", options = ScanOptions(chunkSize = 64 * 1024))
        delay(150)
        server.dropAllClients()
        val st = awaitDone()
        // The interrupted read is repeated on a fresh session (reads are idempotent) and the scan completes.
        assertTrue(st.toString(), st is ScanStatus.Completed)
        assertEquals(before, addresses())
        assertTrue(client.reconnectCount.get() >= 1)
    }

    @Test fun disconnectDuringScanFailsSafelyWithoutAutoReconnect() = runBlocking {
        val c2 = Ps4DebugClient(maxReadChunk = 256 * 1024)
        c2.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 5000, autoReconnect = false))
        val ctl = ScanController(c2, scope)
        ctl.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000")
        withTimeout(30_000) { ctl.status.first { it !is ScanStatus.Running } }
        val before = ctl.results()!!.let { r -> (0 until r.count).map { r.address(it) } }
        server.faults.readDelayMs = 100
        ctl.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000", options = ScanOptions(chunkSize = 64 * 1024))
        delay(150)
        server.dropAllClients()
        val st = withTimeout(30_000) { ctl.status.first { it !is ScanStatus.Running } }
        assertTrue(st.toString(), st is ScanStatus.Failed)
        assertEquals(before, ctl.results()!!.let { r -> (0 until r.count).map { r.address(it) } })
        c2.disconnect()
    }

    @Test fun processTerminationBetweenScans() = runBlocking {
        controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000")
        awaitDone()
        val before = addresses()
        server.killProcess(pid)
        controller.nextScan(ScanMode.UNCHANGED, null)
        val st = awaitDone()
        assertTrue(st is ScanStatus.Failed)
        assertEquals(before, addresses())
    }

    @Test fun emptyMemoryMapIsReported() = runBlocking {
        server.addProcess(MockPs4DebugServer.Process(504, "empty.elf"))
        controller.firstScan(504, "empty.elf", ValueType.INT32, ScanMode.EXACT, "1")
        val st = awaitDone()
        assertTrue(st is ScanStatus.Failed && st.message.contains("No memory regions"))
    }

    @Test fun unmappedCandidatesAreDropped() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(505, "remap.elf"))
        val r1 = p.region("r1", 0x60_0000, 4096, PROT_R or PROT_W)
        val r2 = p.region("r2", 0x70_0000, 4096, PROT_R or PROT_W)
        int32(99).copyInto(r1.data, 8); int32(99).copyInto(r2.data, 8)
        controller.firstScan(505, "remap.elf", ValueType.INT32, ScanMode.EXACT, "99")
        awaitDone()
        assertEquals(2, addresses().size)
        p.regions.remove(r2)
        controller.nextScan(ScanMode.UNCHANGED, null)
        awaitDone()
        assertEquals(listOf(0x60_0008L), addresses())
    }

    @Test fun unknownInitialValueWorkflow() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(506, "health.elf"))
        val r = p.region("heap", 0x80_0000, 64 * 1024, PROT_R or PROT_W)
        kotlin.random.Random(7).nextBytes(r.data)
        val healthAddr = 0x80_0000L + 0x1230
        server.poke(506, healthAddr, ValueCodec.encodeDouble(ValueType.FLOAT32, 87.25))
        controller.firstScan(506, "health.elf", ValueType.FLOAT32, ScanMode.UNKNOWN_INITIAL, null)
        awaitDone()
        assertNotNull(controller.session.value!!.snapshotCandidates)
        assertEquals(16384L, controller.session.value!!.snapshotCandidates)

        server.poke(506, healthAddr, ValueCodec.encodeDouble(ValueType.FLOAT32, 60.0))
        controller.nextScan(ScanMode.DECREASED, null)
        awaitDone()
        // Only the health value changed in memory, so it is the only decreased candidate.
        assertEquals(listOf(healthAddr), addresses())
        assertEquals(null, controller.session.value!!.snapshotCandidates)
    }

    @Test fun snapshotLimitIsEnforced() = runBlocking {
        controller.firstScan(
            pid, "eboot.bin", ValueType.INT32, ScanMode.UNKNOWN_INITIAL, null,
            options = ScanOptions(maxSnapshotBytes = 1024),
        )
        val st = awaitDone()
        assertTrue(st is ScanStatus.Failed && st.message.contains("limit"))
    }

    @Test fun repeatedScansAreDeterministic() = runBlocking {
        repeat(3) {
            controller.firstScan(pid, "eboot.bin", ValueType.INT16, ScanMode.EXACT, "0")
            awaitDone()
        }
        assertEquals(bruteForce(byteArrayOf(0, 0)), addresses())
    }

    @Test fun sessionsArePerProcess() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(507, "other.elf"))
        val r = p.region("d", 0x90_0000, 4096, PROT_R or PROT_W)
        int32(5000).copyInto(r.data, 0)
        controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000"); awaitDone()
        val gameResults = addresses()
        controller.firstScan(507, "other.elf", ValueType.INT32, ScanMode.EXACT, "5000"); awaitDone()
        assertEquals(listOf(0x90_0000L), addresses())
        controller.activate(pid)
        assertEquals(gameResults, addresses())
        assertEquals(pid, controller.session.value!!.pid)
        assertFalse(gameResults.contains(0x90_0000L))
    }

    @Test fun nextScanWithoutFirstScanIsRejected() {
        controller.activate(pid)
        try {
            controller.nextScan(ScanMode.CHANGED, null); throw AssertionError()
        } catch (e: IllegalStateException) { /* ok */ }
    }

    @Test fun pagination() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(508, "page.elf"))
        val r = p.region("d", 0xA0_0000, 4096, PROT_R or PROT_W)
        for (i in 0 until 120) int32(31337).copyInto(r.data, i * 32)
        controller.firstScan(508, "page.elf", ValueType.INT32, ScanMode.EXACT, "31337"); awaitDone()
        val res = controller.results()!!
        assertEquals(3, res.pageCountFor(50))
        assertEquals(50, controller.readPage(0, 50).size)
        val last = controller.readPage(2, 50)
        assertEquals(20, last.size)
        assertEquals(0xA0_0000L + 119 * 32, last.last().address)
        assertTrue(controller.readPage(3, 50).isEmpty())
        assertTrue(last.all { !it.changed })
    }
}
