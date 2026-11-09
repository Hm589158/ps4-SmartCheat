package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.asm.CodePatchPlanner
import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.pointer.PointerScanner
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Hex
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Saving an Assembly patch: the stored entry must be on disk, readable after a restart (new repository instance),
 * in a new or an existing profile; failures change nothing. Also the real-PS4 memory map naming ("executable"),
 * which made every code patch on hardware "not inside a module image".
 */
class CodePatchSaveTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = tmp.root.resolve("cheats.json")
    private var n = 0
    private fun repo(f: File = file) = CheatRepository(f, idGenerator = { "id${++n}" })

    private fun patchEntry(id: String, module: ModuleRef = ModuleRef("executable", 0x2006)) = CheatEntry(
        id, "No coin loss", ValueType.INT8, 0x402006, module, originalBytes = "83E801", patchBytes = "909090",
        assembly = "nop", group = "Coins", signature = "83 E8 01 89 05 ?? ?? ?? ?? C3",
    )

    @Test fun patchInANewProfileIsStoredAtomicallyAndSurvivesARestart() = runBlocking {
        val r = repo()
        r.load()
        val p = r.createProfileWithCheat("MOCK00001", "MOCK00001", "", "eboot.bin", patchEntry("c1"))
        assertTrue(r.verifyStored(p.id, "c1"))
        assertNull(p.gameVersion) // blank version is stored as unknown
        // "Restart": a new repository instance reads the file.
        val again = repo().also { it.load() }
        val saved = again.profiles.value.single().cheats.single()
        assertEquals(patchEntry("c1"), saved)
        assertTrue(saved.isPatch)
    }

    @Test fun patchInAnExistingProfileIsAddedAndVerified() = runBlocking {
        val r = repo()
        r.load()
        val p = r.createProfile("Demo", "MOCK00001", "01.00", "eboot.bin")
        r.upsertCheat(p.id, CheatEntry("v1", "Coins", ValueType.INT32, 0x500120, ModuleRef("executable", 0x100120), "999", "88130000"))
        r.upsertCheat(p.id, patchEntry("c2"))
        assertTrue(r.verifyStored(p.id, "c2"))
        assertFalse(r.verifyStored(p.id, "nope"))
        assertEquals(listOf("v1", "c2"), repo().also { it.load() }.profiles.value.single().cheats.map { it.id })
    }

    @Test fun failuresChangeNothing() = runBlocking {
        val r = repo()
        r.load()
        val p = r.createProfile("Demo", null, null, "eboot.bin")
        val before = file.readText()
        // Unknown profile.
        try { r.upsertCheat("missing", patchEntry("x")); fail() } catch (_: IllegalArgumentException) {}
        // An entry that would make the whole library unreadable at the next start is refused before writing.
        try { r.upsertCheat(p.id, patchEntry("bad").copy(originalBytes = "83E8")); fail() } catch (_: CheatFormatException) {}
        try { r.createProfileWithCheat(" ", null, null, "eboot.bin", patchEntry("y")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(before, file.readText())
        assertEquals(listOf(p.id), r.profiles.value.map { it.id })
        assertTrue(r.profiles.value.single().cheats.isEmpty())
    }

    @Test fun storageFailureIsReportedAndStateIsUnchanged() = runBlocking {
        val dir = tmp.newFolder("ro")
        val f = File(dir, "cheats.json")
        val r = repo(f)
        r.load()
        dir.setWritable(false)
        try {
            if (dir.canWrite()) return@runBlocking // running as root: permissions are not enforced
            try { r.createProfileWithCheat("Demo", null, null, "eboot.bin", patchEntry("c1")); fail() } catch (_: java.io.IOException) {}
            assertTrue(r.profiles.value.isEmpty())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test fun savingRightAfterStartWaitsForTheStoredLibrary() = runBlocking {
        val first = repo().also { it.load() }
        first.createProfile("Existing", null, null, "eboot.bin")
        // A new instance that was not loaded yet (app just started): the save must not overwrite the file.
        val r = repo()
        r.createProfileWithCheat("New", null, null, "eboot.bin", patchEntry("c9"))
        assertEquals(setOf("Existing", "New"), repo().also { it.load() }.profiles.value.map { it.name }.toSet())
    }

    // ------------------------------------------------------------------ real PS4 naming ("executable")

    private val realMap = listOf(
        MemoryRegion("executable", 0x400000, 0x410000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_EXEC),
        MemoryRegion("executable", 0x500000, 0x510000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_WRITE),
        MemoryRegion("libkernel.sprx", 0x800000, 0x810000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_EXEC),
        MemoryRegion("anon:heap", 0x200000000, 0x200400000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_WRITE),
    )

    @Test fun executableMappingIsTheGameModule() {
        assertEquals(ModuleRef("executable", 0x2006), ModuleResolver.refFor(0x402006, realMap))
        assertEquals(ModuleRef("executable", 0x100120), ModuleResolver.refFor(0x500120, realMap))
        assertEquals(ModuleRef("libkernel.sprx", 0x10), ModuleResolver.refFor(0x800010, realMap))
        assertNull(ModuleResolver.refFor(0x200000010, realMap))
        // References saved under either naming resolve on both maps.
        assertEquals(0x402006L, ModuleResolver.resolve(ModuleRef("eboot.bin", 0x2006), realMap))
        val mockMap = realMap.map { if (it.name == "executable") it.copy(name = "eboot.bin") else it }
        assertEquals(0x402006L, ModuleResolver.resolve(ModuleRef("executable", 0x2006), mockMap))
        assertTrue(ModuleResolver.sameModule("executable", "eboot.bin"))
        assertFalse(ModuleResolver.sameModule("libkernel.sprx", "eboot.bin"))
        // The executable's data is static for pointer scans.
        assertTrue(PointerScanner.isStatic(realMap[1]))
        assertFalse(PointerScanner.isStatic(realMap[0]))
    }

    @Test fun goldHenExportAndLibrarySwitchesAcceptTheExecutableName() {
        val profile = CheatProfile("p", "Demo", "MOCK00001", "01.00", "eboot.bin", cheats = listOf(patchEntry("c1")))
        val r = GoldHenExporter.export(profile, "Demo", emptyList())
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals(0x2006L, GoldHenImport.parse(r.json!!).mods.single().patches.single().offset)
        val f = CodeCheats.asGoldHenFile(profile)!!
        assertTrue(f.mods.single().problems.toString(), f.mods.single().applicable)
    }

    @Test fun patchPlannedOnARealStyleMapIsSavedAndTogglableFromTheLibrary() = runBlocking {
        val server = MockPs4DebugServer()
        val client = Ps4DebugClient()
        try {
            // A game whose map uses the PS4 kernel's names.
            val p = server.addProcess(MockPs4DebugServer.Process(91, "eboot.bin", titleId = "REAL00001", path = "/app0/eboot.bin"))
            p.region("executable", 0x400000, 0x10000, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_X)
            p.region("executable", 0x500000, 0x10000, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
            server.poke(91, DemoGame.SPEND_ROUTINE, DemoGame.SPEND_CODE)
            client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
            val regions = client.getMemoryRegions(91)
            val window = client.readMemory(91, DemoGame.SUB_ONE, CodePatchPlanner.WINDOW_BYTES)
            val patch = CodePatchPlanner.plan(DemoGame.SUB_ONE, window, byteArrayOf(0x90.toByte()), "nop", regions.regionFor(DemoGame.SUB_ONE))
            val module = ModuleResolver.refFor(patch.address, regions)
            assertNotNull("code in the \"executable\" mapping must be saveable", module)
            assertEquals(ModuleRef("executable", 0x2006), module)
            val entry = patchEntry("c1", module!!).copy(originalBytes = Hex.encode(patch.off), patchBytes = Hex.encode(patch.on))
            val repo = repo().also { it.load() }
            val prof = repo.createProfileWithCheat("REAL00001", "REAL00001", "01.00", "eboot.bin", entry)
            assertTrue(repo.verifyStored(prof.id, "c1"))
            val stored = repo().also { it.load() }.profiles.value.single()
            val file = CodeCheats.asGoldHenFile(stored)!!
            val check = GoldHenImport.check(client, 91, file, file.mods.single(), regions)
            assertEquals(listOf(DemoGame.SUB_ONE), check.addresses)
            assertTrue(check.canEnable)
        } finally {
            runBlocking { client.disconnect() }
            server.close()
        }
    }
}
