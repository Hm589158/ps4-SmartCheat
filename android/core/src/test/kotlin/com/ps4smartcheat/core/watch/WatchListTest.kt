package com.ps4smartcheat.core.watch

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class WatchListTest {
    @get:Rule val tmp = TemporaryFolder()

    private var ids = 0
    private fun repo(file: java.io.File = tmp.root.resolve("addresses.json")) =
        WatchRepository(file, clock = { 1000L }, idGenerator = { "w-${ids++}" })

    private val eboot = listOf(
        MemoryRegion("eboot.bin", 0x400000, 0x410000, 0, 5),
        MemoryRegion("eboot.bin", 0x500000, 0x510000, 0, 3),
        MemoryRegion("anon", 0x2000000000, 0x2000400000, 0, 3),
    )

    private fun entry(address: Long, type: ValueType = ValueType.INT32, pid: Int = 77, regions: List<MemoryRegion> = eboot, label: String = "x") =
        WatchEntry("", label, type, address, ModuleResolver.refFor(address, regions), pid)

    @Test fun listsArePerGameAndSurviveRestart() = runBlocking {
        val r1 = repo(); r1.load()
        r1.add("CUSA00001", "eboot.bin", entry(0x500120, label = " Coins "))
        r1.add("CUSA00002", "eboot.bin", entry(0x500200, label = "Other game"))
        r1.add(null, "homebrew.elf", entry(0x500300, label = "No title"))
        val r2 = repo(); r2.load()
        assertEquals(r1.games.value, r2.games.value)
        assertEquals(listOf("Coins"), r2.entries("CUSA00001").map { it.label }) // trimmed, other games not mixed in
        assertEquals(listOf("No title"), r2.entries(WatchKeys.gameKey(null, "homebrew.elf")).map { it.label })
        assertTrue(r2.entries(WatchKeys.gameKey(null, "eboot.bin")).isEmpty()) // untitled process != titled game
        assertNull(r2.loadError)
    }

    @Test fun duplicatesAreNotAddedAndEntriesCanBeEditedAndRemoved() = runBlocking {
        val r = repo(); r.load()
        val a = r.add("CUSA00001", "eboot.bin", entry(0x500120))
        assertFalse(a.alreadyPresent)
        val again = r.add("CUSA00001", "eboot.bin", entry(0x500120, pid = 123))
        assertTrue(again.alreadyPresent) // same module offset in a later session
        assertEquals(a.entry.id, again.entry.id)
        val other = r.add("CUSA00001", "eboot.bin", entry(0x500120, ValueType.FLOAT32))
        assertFalse(other.alreadyPresent) // a different type is a different entry
        r.update("CUSA00001", a.entry.copy(label = "Coins  ", notes = "shop"))
        assertEquals("Coins", r.entries("CUSA00001").first().label)
        r.remove("CUSA00001", a.entry.id)
        r.remove("CUSA00001", other.entry.id)
        assertNull(r.list("CUSA00001")) // empty lists are dropped
    }

    @Test fun corruptFileIsPreserved() = runBlocking {
        val f = tmp.root.resolve("addresses.json")
        f.writeText("{ not json")
        val r = repo(f); r.load()
        assertNotNull(r.loadError)
        assertTrue(r.games.value.isEmpty())
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("addresses.json.corrupt-") })
    }

    @Test fun resolvesModuleRelativeAfterRelocation() {
        val e = entry(0x500120)
        assertEquals(WatchStatus.MODULE_RELATIVE, WatchResolver.resolve(e, 77, eboot).status)
        // The game restarts with the executable loaded elsewhere: the module-relative form follows it.
        val moved = listOf(
            MemoryRegion("eboot.bin", 0x800000, 0x810000, 0, 5),
            MemoryRegion("eboot.bin", 0x900000, 0x910000, 0, 3),
        )
        val r = WatchResolver.resolve(e, 123, moved)
        assertEquals(WatchStatus.MODULE_RELATIVE, r.status)
        assertEquals(0x900120L, r.address)
    }

    @Test fun absoluteAddressesAreOnlyTrustedInTheirSession() {
        val heap = entry(0x2000000010)
        assertNull(heap.module)
        assertEquals(WatchStatus.SAME_SESSION, WatchResolver.resolve(heap, 77, eboot).status)
        assertEquals(WatchStatus.ABSOLUTE_UNVERIFIED, WatchResolver.resolve(heap, 123, eboot).status)
        // Not mapped any more: never read.
        val r = WatchResolver.resolve(heap, 123, eboot.take(2))
        assertEquals(WatchStatus.UNMAPPED, r.status)
        assertFalse(r.status.readable)
        // A value straddling the end of a region is unmapped too.
        assertEquals(WatchStatus.UNMAPPED, WatchResolver.resolve(entry(0x2000400000 - 2), 77, eboot).status)
    }

    @Test fun readPlanMergesNearbyEntriesButNeverCrossesRegions() {
        val items = listOf(
            entry(0x500120), entry(0x500124), entry(0x500180, ValueType.INT64), // one span
            entry(0x508000), // too far: own span
            entry(0x2000000000), // other region
            entry(0x7000000000), // unmapped: skipped
        ).mapIndexed { i, e -> WatchResolver.resolve(e.copy(id = "e$i"), 77, eboot) }
        val spans = WatchReader(maxGap = 256).plan(items)
        assertEquals(3, spans.size)
        assertEquals(0x500120L, spans[0].start)
        assertEquals(0x188 - 0x120, spans[0].length)
        assertEquals(listOf("e0", "e1", "e2"), spans[0].members.map { it.entry.id })
        assertTrue(spans.none { s -> s.members.any { it.entry.id == "e5" } })
        // Adjacent regions are never merged even when the gap is tiny.
        val adj = listOf(MemoryRegion("a", 0x1000, 0x2000, 0, 3), MemoryRegion("b", 0x2000, 0x3000, 0, 3))
        val two = listOf(entry(0x1FFC, regions = adj), entry(0x2000, regions = adj)).mapIndexed { i, e -> WatchResolver.resolve(e.copy(id = "a$i"), 77, adj) }
        assertEquals(2, WatchReader().plan(two).size)
    }

    private val server = MockPs4DebugServer().also { DemoGame.install(it) }
    private val client = Ps4DebugClient()

    @After fun close() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private class CountingBackend(private val d: DebugBackend) : DebugBackend by d {
        val reads = AtomicInteger()
        override suspend fun readMemory(pid: Int, address: Long, length: Int): ByteArray {
            reads.incrementAndGet()
            return d.readMemory(pid, address, length)
        }
    }

    @Test fun readsLiveValuesFromMockServerWithBatching() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(DemoGame.PID)
        val coins = WatchEntry("coins", "Coins", ValueType.INT32, DemoGame.COINS_ADDRESS, ModuleResolver.refFor(DemoGame.COINS_ADDRESS, regions), DemoGame.PID)
        val next = coins.copy(id = "next", address = DemoGame.COINS_ADDRESS + 4, module = ModuleResolver.refFor(DemoGame.COINS_ADDRESS + 4, regions))
        val gone = coins.copy(id = "gone", address = 0x7000000000, module = null)
        server.pokeInt32(DemoGame.PID, DemoGame.COINS_ADDRESS + 4, 42)
        val counting = CountingBackend(client)
        val resolved = WatchResolver.resolveAll(listOf(coins, next, gone), DemoGame.PID, regions)
        val values = WatchReader().read(counting, DemoGame.PID, resolved)
        assertEquals(1, counting.reads.get())
        assertEquals("5000", ValueCodec.format(ValueType.INT32, values.getValue("coins")))
        assertEquals("42", ValueCodec.format(ValueType.INT32, values.getValue("next")))
        assertFalse(values.containsKey("gone"))
        assertTrue(client.state.value is com.ps4smartcheat.core.backend.ConnectionState.Connected) // no failing read
    }

    @Test fun moduleEntryFollowsGameRestart() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(DemoGame.PID)
        val coins = WatchEntry("coins", "Coins", ValueType.INT32, DemoGame.COINS_ADDRESS, ModuleResolver.refFor(DemoGame.COINS_ADDRESS, regions), DemoGame.PID)
        assertNotNull(coins.module)
        val restarted = server.restartProcess(DemoGame.PID, 123)
        server.pokeInt32(restarted.pid, DemoGame.COINS_ADDRESS, 777)
        val r = WatchResolver.resolve(coins, 123, client.getMemoryRegions(123))
        assertEquals(WatchStatus.MODULE_RELATIVE, r.status)
        assertEquals("777", ValueCodec.format(ValueType.INT32, WatchReader().read(client, 123, listOf(r)).getValue("coins")))
    }
}

class WatchImportExportTest {
    @get:Rule val tmp = TemporaryFolder()
    private var ids = 0
    private fun repo(name: String) = WatchRepository(tmp.root.resolve(name), clock = { 5L }, idGenerator = { "id-${ids++}" })
    private val regions = listOf(MemoryRegion("eboot.bin", 0x400000, 0x410000, 0, 5), MemoryRegion("eboot.bin", 0x500000, 0x510000, 0, 3))
    private fun e(a: Long, label: String) = WatchEntry("", label, ValueType.INT32, a, ModuleResolver.refFor(a, regions), 77)

    @Test fun exportImportRoundTripMergesWithoutDuplicates() = runBlocking {
        val a = repo("a.json"); a.load()
        a.add("CUSA00001", "eboot.bin", e(0x500120, "Coins"))
        a.add("CUSA00001", "eboot.bin", e(0x500124, "Gems"))
        a.add("CUSA00002", "eboot.bin", e(0x500200, "Other"))
        val onlyFirst = a.exportJson(listOf("CUSA00001"))
        assertFalse(onlyFirst.contains("Other"))

        val b = repo("b.json"); b.load()
        b.add("CUSA00001", "eboot.bin", e(0x500120, "Coins (mine)"))
        val r = b.importJson(a.exportJson())
        assertEquals(WatchImportResult(added = 2, skipped = 1, games = 2), r)
        assertEquals(listOf("Coins (mine)", "Gems"), b.entries("CUSA00001").map { it.label }) // existing entry kept
        assertEquals(listOf("Other"), b.entries("CUSA00002").map { it.label })
        assertEquals(3, b.games.value.flatMap { it.entries }.map { it.id }.toSet().size) // fresh, unique ids
        // Importing the same file again adds nothing.
        assertEquals(0, b.importJson(a.exportJson()).added)
    }

    @Test fun invalidFilesAreRejectedWithoutChanges() = runBlocking {
        val b = repo("b.json"); b.load()
        b.add("CUSA00001", "eboot.bin", e(0x500120, "Coins"))
        val before = b.games.value
        for (bad in listOf("nope", "{\"format\":\"ps4-smartcheat-cheats\",\"profiles\":[]}", "{\"format\":\"ps4-smartcheat-addresses\",\"formatVersion\":99,\"games\":[]}")) {
            try { b.importJson(bad); throw AssertionError("accepted $bad") } catch (_: WatchFormatException) { }
        }
        assertEquals(before, b.games.value)
    }
}
