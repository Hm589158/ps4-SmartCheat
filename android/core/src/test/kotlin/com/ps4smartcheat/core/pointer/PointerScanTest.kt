package com.ps4smartcheat.core.pointer

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.cheat.CheatJson
import com.ps4smartcheat.core.cheat.ModuleRef
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_R
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_W
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_X
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class PointerScanTest {
    @Test fun parallelSortMatchesReference() {
        val rnd = Random(3)
        for (n in listOf(0, 1, 2, 15, 16, 17, 1000, 50_000)) {
            val keys = LongArray(n) { if (it % 7 == 0) 42L else rnd.nextLong() } // duplicates and "negative" (high) values
            val other = LongArray(n) { keys[it] xor 0x5555 }
            ParallelSort.sortByValue(keys, other, n)
            for (i in 1 until n) assertTrue(java.lang.Long.compareUnsigned(keys[i - 1], keys[i]) <= 0)
            for (i in 0 until n) assertEquals(keys[i] xor 0x5555, other[i]) // pairs stay together
        }
    }

    @Test fun chainDescriptionAndJson() {
        val c = PointerChain(ModuleRef("eboot.bin", 0x100200), listOf(0x10, 0x48))
        assertEquals("[[eboot.bin+0x100200]+0x10]+0x48", c.describe())
        val json = CheatJson.json.encodeToString(PointerChain.serializer(), c)
        assertTrue(json, json.contains("\"0x000000010\""))
        assertEquals(c, CheatJson.json.decodeFromString(PointerChain.serializer(), json))
    }

    private val server = MockPs4DebugServer()
    private val client = Ps4DebugClient()

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private fun MockPs4DebugServer.Region.putLong(at: Long, v: Long) =
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong((at - start).toInt(), v)

    private val textStart = 0x40_0000L
    private val dataStart = 0x50_0000L

    /**
     * A small game: static `eboot.bin` data holds a pointer to a heap "world" object, which points to a "player"
     * object whose field +0x48 is the target value. [heapBase] moves between runs like an ASLR'd heap.
     */
    private fun game(pid: Int, heapBase: Long, noise: Boolean = true): Long {
        val p = server.addProcess(MockPs4DebugServer.Process(pid, "eboot.bin", titleId = "CUSA00001", path = "/app0/eboot.bin"))
        p.region("eboot.bin", textStart, 0x1000, PROT_R or PROT_X)
        val data = p.region("eboot.bin", dataStart, 0x1000, PROT_R or PROT_W)
        val heap = p.region("anon:heap", heapBase, 0x10000, PROT_R or PROT_W)
        if (noise) Random(pid).nextBytes(heap.data) // random bytes rarely look like aligned in-range pointers
        val world = heapBase + 0x5000
        val player = heapBase + 0x1000
        data.putLong(dataStart + 0x200, world)
        heap.putLong(world + 0x10, player)
        ByteBuffer.wrap(heap.data).order(ByteOrder.LITTLE_ENDIAN).putInt((player + 0x48 - heapBase).toInt(), 1234)
        return player + 0x48
    }

    @Test fun findsChainFromStaticDataAndItSurvivesARestart() = runBlocking {
        val target = game(10, 0x20_0000_0000)
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(10)
        val map = PointerScanner.buildMap(client, 10, PointerScanner.mapRegions(regions), regions)
        assertFalse(map.truncated)
        val res = PointerScanner.findChains(map, target, regions, PointerScanOptions(maxDepth = 3, maxOffset = 0x100))
        val expected = PointerChain(ModuleRef("eboot.bin", dataStart + 0x200 - textStart), listOf(0x10, 0x48))
        assertTrue("chains: ${res.chains.map { it.describe() }}", expected in res.chains)
        assertEquals(PointerResolution.Resolved(target), PointerResolver.resolve(client, 10, expected, regions, 4))
        // Every reported chain really leads to the target now.
        for (c in res.chains) assertEquals(c.describe(), PointerResolution.Resolved(target), PointerResolver.resolve(client, 10, c, regions))

        // The game restarts with its heap elsewhere: the chain follows; a decoy chain is filtered out.
        server.killProcess(10)
        val newTarget = game(11, 0x30_0000_0000)
        val regions2 = client.getMemoryRegions(11)
        assertEquals(PointerResolution.Resolved(newTarget), PointerResolver.resolve(client, 11, expected, regions2, 4))
        val decoy = PointerChain(ModuleRef("eboot.bin", dataStart + 0x200 - textStart), listOf(0x18, 0x48))
        val kept = PointerScanner.filter(client, 11, res.chains + decoy, regions2, newTarget)
        assertTrue(expected in kept)
        assertFalse(decoy in kept)
        assertTrue(client.state.value is ConnectionState.Connected) // no read ever touched unmapped memory
    }

    @Test fun brokenChainsNeverReadUnmappedMemory() = runBlocking {
        game(12, 0x20_0000_0000, noise = false)
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(12)
        // Static data +0x208 holds zero, so the next level would read address 0x8 (unmapped).
        val broken = PointerChain(ModuleRef("eboot.bin", dataStart + 0x208 - textStart), listOf(0x8, 0x0))
        assertTrue(PointerResolver.resolve(client, 12, broken, regions) is PointerResolution.Broken)
        val missing = PointerChain(ModuleRef("libfoo.sprx", 0), listOf(0x0))
        assertTrue(PointerResolver.resolve(client, 12, missing, regions) is PointerResolution.Broken)
        assertTrue(client.state.value is ConnectionState.Connected)
    }

    @Test fun limitsAreEnforced() = runBlocking {
        val target = game(13, 0x20_0000_0000, noise = false)
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(13)
        val tiny = PointerScanner.buildMap(client, 13, PointerScanner.mapRegions(regions), regions, PointerScanOptions(maxPointers = 1))
        assertTrue(tiny.truncated)
        assertEquals(1L, tiny.pointerCount)
        try {
            PointerScanner.buildMap(client, 13, PointerScanner.mapRegions(regions), regions, PointerScanOptions(maxScanBytes = 1024))
            throw AssertionError("expected limit")
        } catch (e: com.ps4smartcheat.core.scan.ScanTooLargeException) { /* expected */ }
        // Depth 1 cannot reach a two-level chain.
        val map = PointerScanner.buildMap(client, 13, PointerScanner.mapRegions(regions), regions)
        assertTrue(PointerScanner.findChains(map, target, regions, PointerScanOptions(maxDepth = 1)).chains.isEmpty())
        val capped = PointerScanner.findChains(map, target, regions, PointerScanOptions(maxDepth = 3, maxResults = 1))
        assertEquals(1, capped.chains.size)
    }
}

class PointerWatchTest {
    private val server = MockPs4DebugServer()
    private val client = Ps4DebugClient()

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    @Test fun pointerEntryIsFollowedOnEveryRead() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(20, "eboot.bin", titleId = "CUSA00001"))
        p.region("eboot.bin", 0x40_0000, 0x1000, PROT_R or PROT_X)
        val data = p.region("eboot.bin", 0x50_0000, 0x1000, PROT_R or PROT_W)
        val heap = p.region("anon:heap", 0x20_0000_0000, 0x10000, PROT_R or PROT_W)
        val bb = ByteBuffer.wrap(heap.data).order(ByteOrder.LITTLE_ENDIAN)
        ByteBuffer.wrap(data.data).order(ByteOrder.LITTLE_ENDIAN).putLong(0x100, 0x20_0000_1000)
        bb.putInt(0x1010, 55)
        bb.putInt(0x2010, 66)
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(20)
        val chain = PointerChain(ModuleRef("eboot.bin", 0x100100), listOf(0x10))
        val e = com.ps4smartcheat.core.watch.WatchEntry("hp", "HP", com.ps4smartcheat.core.scan.ValueType.INT32, 0x20_0000_1010, null, 20, pointer = chain)
        val offline = com.ps4smartcheat.core.watch.WatchResolver.resolveAll(listOf(e), 20, regions).single()
        assertEquals(com.ps4smartcheat.core.watch.WatchStatus.POINTER_UNRESOLVED, offline.status)
        assertFalse(offline.status.readable)
        val reader = com.ps4smartcheat.core.watch.WatchReader()
        fun read() = runBlocking {
            val live = com.ps4smartcheat.core.watch.WatchResolver.resolveLive(client, 20, listOf(e), regions)
            live.single() to reader.read(client, 20, live)["hp"]
        }
        val (r1, v1) = read()
        assertEquals(com.ps4smartcheat.core.watch.WatchStatus.POINTER, r1.status)
        assertEquals(55, ByteBuffer.wrap(v1!!).order(ByteOrder.LITTLE_ENDIAN).int)
        // The game moves the object: the entry follows the pointer.
        ByteBuffer.wrap(data.data).order(ByteOrder.LITTLE_ENDIAN).putLong(0x100, 0x20_0000_2000)
        assertEquals(66, ByteBuffer.wrap(read().second!!).order(ByteOrder.LITTLE_ENDIAN).int)
        // Object gone (null pointer): broken, nothing read.
        ByteBuffer.wrap(data.data).order(ByteOrder.LITTLE_ENDIAN).putLong(0x100, 0)
        val (r3, v3) = read()
        assertEquals(com.ps4smartcheat.core.watch.WatchStatus.POINTER_BROKEN, r3.status)
        assertEquals(null, v3)
        assertTrue(client.state.value is ConnectionState.Connected)
    }
}

class SpilledPointerIndexTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()
    private val server = MockPs4DebugServer()
    private val client = Ps4DebugClient()

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    @Test fun spilledRunsGiveTheSameChainsAsTheInMemoryMapAndAreDeleted() = runBlocking {
        // Many heap pointers (an array of object pointers) force several runs with a tiny run size.
        val p = server.addProcess(MockPs4DebugServer.Process(30, "eboot.bin", titleId = "CUSA00001"))
        p.region("eboot.bin", 0x40_0000, 0x1000, PROT_R or PROT_X)
        val data = p.region("eboot.bin", 0x50_0000, 0x1000, PROT_R or PROT_W)
        val heapBase = 0x20_0000_0000L
        val heap = p.region("anon:heap", heapBase, 0x20000, PROT_R or PROT_W)
        val hb = ByteBuffer.wrap(heap.data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 1000) hb.putLong(0x8000 + i * 8, heapBase + 0x100 * (i % 64)) // noise pointers
        ByteBuffer.wrap(data.data).order(ByteOrder.LITTLE_ENDIAN).putLong(0x200, heapBase + 0x1000)
        hb.putLong(0x1000 + 0x10, heapBase + 0x3000)
        val target = heapBase + 0x3000 + 0x48
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(30)
        val opts = PointerScanOptions(maxDepth = 3, maxOffset = 0x100, runSize = 100)
        val mem = PointerScanner.buildMap(client, 30, PointerScanner.mapRegions(regions), regions, opts)
        val spilled = PointerScanner.buildMap(client, 30, PointerScanner.mapRegions(regions), regions, opts, spillDir = tmp.root)
        assertTrue(spilled is SpilledPointerIndex)
        assertTrue((spilled as SpilledPointerIndex).runCount > 5)
        assertEquals(mem.pointerCount, spilled.pointerCount)
        val a = PointerScanner.findChains(mem, target, regions, opts).chains.toSet()
        val b = PointerScanner.findChains(spilled, target, regions, opts).chains.toSet()
        assertEquals(a, b)
        assertTrue(PointerChain(ModuleRef("eboot.bin", 0x100200), listOf(0x10, 0x48)) in b)
        assertEquals(PointerScanner.estimateBytes(regions), spilled.bytesScanned)
        spilled.close()
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".run") })
    }
}
