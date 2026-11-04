package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.ProcessInfo
import com.ps4smartcheat.core.scan.ValueType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheatLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var ids = 0
    private fun repo(file: java.io.File = tmp.root.resolve("cheats.json")) =
        CheatRepository(file, clock = { 1000L }, idGenerator = { "id-${ids++}" })

    private val eboot = listOf(
        MemoryRegion("eboot.bin", 0x400000, 0x410000, 0, 5),
        MemoryRegion("eboot.bin", 0x500000, 0x510000, 0, 3),
        MemoryRegion("anon", 0x2000000000, 0x2000400000, 0, 3),
    )

    private fun cheat(id: String, address: Long, regions: List<MemoryRegion> = eboot) = CheatEntry(
        id = id, name = "Coins $id", type = ValueType.INT32, address = address,
        module = ModuleResolver.refFor(address, regions), replacementValue = "99999", originalBytes = "88130000",
        discoveredPid = 77,
    )

    @Test fun profilesSurviveRestart() = runBlocking {
        val r1 = repo(); r1.load()
        val p = r1.createProfile("My game", "CUSA00001", "01.00", "eboot.bin")
        r1.upsertCheat(p.id, cheat("c1", 0x500120))
        val r2 = repo(); r2.load()
        assertEquals(r1.profiles.value, r2.profiles.value)
        assertEquals(0x500120L, r2.profiles.value.single().cheats.single().address)
        assertNull(r2.loadError)
    }

    @Test fun jsonRoundTrip() = runBlocking {
        val r = repo(); r.load()
        val p = r.createProfile("G", "CUSA00001", "01.00", "eboot.bin")
        r.upsertCheat(p.id, cheat("c1", 0x500120))
        r.upsertCheat(p.id, cheat("c2", 0x2000000010).copy(type = ValueType.FLOAT32, replacementValue = "1.5", originalBytes = "0000803F"))
        val text = r.exportJson()
        assertTrue(text.contains("\"0x000500120\""))
        val decoded = CheatJson.decode(text)
        assertEquals(r.profiles.value, decoded.profiles)

        val other = repo(tmp.root.resolve("other.json")); other.load()
        val res = other.importJson(text)
        assertEquals(1, res.added)
        assertEquals(r.profiles.value, other.profiles.value)
        // Importing again replaces instead of duplicating.
        assertEquals(1, other.importJson(text).replaced)
        assertEquals(1, other.profiles.value.size)
    }

    @Test fun invalidFilesAreRejected() {
        val bad = listOf(
            "not json",
            """{"format":"other","formatVersion":1,"profiles":[]}""",
            """{"format":"ps4-smartcheat-cheats","formatVersion":99,"profiles":[]}""",
            """{"format":"ps4-smartcheat-cheats","formatVersion":1,"profiles":[{"id":"a","name":"x","processName":"e","cheats":[{"id":"1","name":"n","type":"INT8","address":"0x10","replacementValue":"999"}]}]}""",
            """{"format":"ps4-smartcheat-cheats","formatVersion":1,"profiles":[{"id":"a","name":"x","processName":"e","cheats":[{"id":"1","name":"n","type":"INT32","address":"zz"}]}]}""",
            """{"format":"ps4-smartcheat-cheats","formatVersion":1,"profiles":[{"id":"a","name":"x","processName":"e","cheats":[{"id":"1","name":"n","type":"INT32","address":"0x1","originalBytes":"00"}]}]}""",
            """{"name":"x","id":"CUSA1","version":"01.00","process":"eboot.bin","mods":[]}""",
        )
        for (b in bad) {
            try { CheatJson.decode(b); fail("accepted: $b") } catch (e: CheatFormatException) { /* ok */ }
        }
    }

    @Test fun corruptLibraryIsPreserved() = runBlocking {
        val f = tmp.root.resolve("cheats.json"); f.writeText("{broken")
        val r = repo(f); r.load()
        assertNotNull(r.loadError)
        assertTrue(r.profiles.value.isEmpty())
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("cheats.json.corrupt-") })
    }

    @Test fun profilesAreIsolatedPerGame() = runBlocking {
        val r = repo(); r.load()
        r.createProfile("A", "CUSA00001", "01.00", "eboot.bin")
        r.createProfile("B", "CUSA00002", "01.00", "eboot.bin")
        r.createProfile("A2", "CUSA00001", "01.01", "eboot.bin")
        assertEquals(listOf("A"), r.profilesForGame("CUSA00001", "01.00", "eboot.bin").map { it.name })
        assertEquals(listOf("A", "A2"), r.profilesForGame("CUSA00001", null, "eboot.bin").map { it.name })
        assertTrue(r.profilesForGame("CUSA00003", null, "eboot.bin").isEmpty())
    }

    @Test fun editAndDelete() = runBlocking {
        val r = repo(); r.load()
        val p = r.createProfile("A", null, null, "eboot.bin")
        r.upsertCheat(p.id, cheat("c1", 0x500120))
        r.upsertCheat(p.id, cheat("c1", 0x500120).copy(name = "Renamed"))
        assertEquals("Renamed", r.profile(p.id)!!.cheats.single().name)
        r.deleteCheat(p.id, "c1")
        assertTrue(r.profile(p.id)!!.cheats.isEmpty())
        r.deleteProfile(p.id)
        assertTrue(r.profiles.value.isEmpty())
    }

    @Test fun moduleRefsAndValidation() {
        val ref = ModuleResolver.refFor(0x500120, eboot)!!
        assertEquals("eboot.bin", ref.name)
        assertEquals(0x100120L, ref.offset) // relative to lowest eboot.bin region
        assertNull(ModuleResolver.refFor(0x2000000010, eboot)) // heap: no module

        val profile = CheatProfile("p", "P", "CUSA00001", "01.00", "eboot.bin", cheats = listOf(cheat("c1", 0x500120), cheat("c2", 0x2000000010)))
        val info = ProcessInfo(77, "eboot.bin", "", "CUSA00001", "")

        // Same session: OK / OK
        val same = ProfileValidator.validate(profile, 77, info, "eboot.bin", eboot)
        assertEquals(listOf(CheatStatus.OK, CheatStatus.OK), same.cheats.map { it.status })
        assertTrue(same.canApply)

        // New session with ASLR shift of 0x10000000: module cheat relocates, heap cheat is flagged.
        val shifted = eboot.map { if (it.name == "eboot.bin") it.copy(start = it.start + 0x10000000, end = it.end + 0x10000000) else it }
        val next = ProfileValidator.validate(profile, 123, info, "eboot.bin", shifted)
        assertEquals(CheatStatus.RELOCATED, next.cheats[0].status)
        assertEquals(0x10500120L, next.cheats[0].resolvedAddress)
        assertEquals(CheatStatus.ABSOLUTE_UNVERIFIED, next.cheats[1].status)

        // Heap unmapped -> not usable.
        val noHeap = ProfileValidator.validate(profile, 123, info, "eboot.bin", eboot.take(2))
        assertEquals(CheatStatus.NOT_MAPPED, noHeap.cheats[1].status)

        // Wrong game.
        val wrong = ProfileValidator.validate(profile, 77, info.copy(titleId = "CUSA99999"), "eboot.bin", eboot)
        assertEquals(false, wrong.titleMatches)
        assertFalse(wrong.canApply)
    }

    @Test fun goldHenExportUsesVerifiedSchemaOnly() {
        val profile = CheatProfile(
            "p", "P", "CUSA00001", "01.00", "eboot.bin",
            cheats = listOf(
                cheat("c1", 0x500120),
                cheat("c2", 0x2000000010), // heap: skipped
                cheat("c3", 0x500200).copy(originalBytes = null), // no "off": skipped
            ),
        )
        val res = GoldHenExporter.export(profile, "Test Game", listOf("me"))
        assertTrue(res.errors.toString(), res.errors.isEmpty())
        assertEquals(1, res.exported)
        assertEquals(2, res.skipped.size)
        val root = Json.parseToJsonElement(res.json!!).jsonObject
        assertEquals(setOf("name", "id", "version", "process", "mods", "credits"), root.keys)
        assertEquals("CUSA00001", root["id"]!!.jsonPrimitive.content)
        val mem = root["mods"]!!.jsonArray[0].jsonObject["memory"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("offset", "on", "off"), mem.keys)
        assertEquals("100120", mem["offset"]!!.jsonPrimitive.content)
        assertEquals("9F860100", mem["on"]!!.jsonPrimitive.content) // 99999 little-endian
        assertEquals("88130000", mem["off"]!!.jsonPrimitive.content)
        assertEquals("CUSA00001_01.00.json", GoldHenExporter.fileName(profile))
    }

    @Test fun goldHenExportRequiresTitleAndVersion() {
        val res = GoldHenExporter.export(CheatProfile("p", "P", null, null, "eboot.bin", cheats = listOf(cheat("c1", 0x500120))), "G", emptyList())
        assertNull(res.json)
        assertEquals(2, res.errors.size)
    }
}
