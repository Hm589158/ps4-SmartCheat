package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.edit.EditPolicy
import com.ps4smartcheat.core.edit.MemoryEditor
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldHenImportTest {
    private val sample = """
        { "name": "Demo", "id": "MOCK00001", "version": "01.00", "process": "eboot.bin", "master": "x",
          "mods": [
            { "name": "Infinite coins", "type": "checkbox", "memory": [ { "offset": "100120", "on": "9F860100", "off": "88130000" } ] },
            { "name": "Two patches", "type": "checkbox", "memory": [
                { "offset": "100400", "on": "01", "off": "00" }, { "offset": "0x100401", "on": "0203", "off": "0000" } ] },
            { "name": "Sectioned", "type": "checkbox", "memory": [ { "section": 1, "offset": "10", "on": "01", "off": "00" } ] },
            { "name": "Button", "type": "button", "memory": [ { "offset": "10", "on": "01", "off": "00" } ] },
            { "name": "Bad", "type": "checkbox", "memory": [ { "offset": "zz", "on": "01", "off": "0000" } ] }
          ],
          "credits": ["someone"] }
    """.trimIndent()

    @Test fun parsesModsAndFlagsWhatCannotBeApplied() {
        val f = GoldHenImport.parse(sample)
        assertEquals("MOCK00001", f.titleId)
        assertEquals("eboot.bin", f.process)
        assertEquals(listOf(true, true, false, false, false), f.mods.map { it.applicable })
        assertEquals(0x100401L, f.mods[1].patches[1].offset)
        assertTrue(f.mods[2].problems.single().contains("section"))
        assertTrue(f.mods[3].problems.single().contains("button"))
        assertTrue(f.notes.any { it.contains("master") })
        assertNull(GoldHenImport.mismatch(f, "MOCK00001", "eboot.bin"))
        assertTrue(GoldHenImport.mismatch(f, "CUSA00002", "eboot.bin")!!.contains("MOCK00001"))
        assertTrue(GoldHenImport.mismatch(f, "MOCK00001", "default.elf") != null)
        for (bad in listOf("[]", "{}", "{\"mods\":[]}", "{\"format\":\"ps4-smartcheat-cheats\",\"mods\":[]}")) {
            try { GoldHenImport.parse(bad); throw AssertionError("accepted $bad") } catch (_: GoldHenImport.FormatException) { }
        }
    }

    @Test fun exporterOutputIsReadBackIdentically() {
        val p = CheatProfile(
            "p", "Demo", "MOCK00001", "01.00", "eboot.bin",
            cheats = listOf(CheatEntry("c", "Coins", ValueType.INT32, DemoGame.COINS_ADDRESS, ModuleRef("eboot.bin", 0x100120), "99999", "88130000")),
        )
        val json = GoldHenExporter.export(p, "Demo", listOf("me")).json!!
        val f = GoldHenImport.parse(json)
        val patch = f.mods.single().patches.single()
        assertEquals(0x100120L, patch.offset)
        assertEquals("9F860100", com.ps4smartcheat.core.util.Hex.encode(patch.on))
        assertEquals("88130000", com.ps4smartcheat.core.util.Hex.encode(patch.off))
    }

    private val server = MockPs4DebugServer().also { DemoGame.install(it) }
    private val client = Ps4DebugClient()

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    @Test fun checkDetectsOffOnAndForeignBuildsAndEnablingGoesThroughTheEditor() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(DemoGame.PID)
        val f = GoldHenImport.parse(sample)
        val coins = f.mods[0]
        val c1 = GoldHenImport.check(client, DemoGame.PID, f, coins, regions)
        assertEquals(listOf(DemoGame.COINS_ADDRESS), c1.addresses)
        assertEquals(PatchState.OFF, c1.state) // 5000 = 88 13 00 00
        assertTrue(c1.canEnable)
        assertFalse(c1.canDisable)

        val editor = MemoryEditor(client, { EditPolicy(editingEnabled = true) })
        for ((i, p) in coins.patches.withIndex()) {
            assertTrue(editor.execute(editor.planBytes(DemoGame.PID, "eboot.bin", c1.addresses[i], p.on)) is WriteOutcome.Verified)
        }
        assertEquals(99999, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
        assertEquals(PatchState.ON, GoldHenImport.check(client, DemoGame.PID, f, coins, regions).state)

        // The game changed the value: neither on nor off → never applicable.
        server.pokeInt32(DemoGame.PID, DemoGame.COINS_ADDRESS, 1234)
        val c3 = GoldHenImport.check(client, DemoGame.PID, f, coins, regions)
        assertEquals(PatchState.MISMATCH, c3.state)
        assertFalse(c3.canEnable || c3.canDisable)

        // Multi-patch mod: all OFF in zeroed memory.
        assertTrue(GoldHenImport.check(client, DemoGame.PID, f, f.mods[1], regions).canEnable)
        // Offsets beyond the image are unmapped and never read.
        val far = f.copy(mods = listOf(coins.copy(patches = listOf(GhPatch(0x7000_0000, byteArrayOf(1), byteArrayOf(0))))))
        assertEquals(PatchState.UNMAPPED, GoldHenImport.check(client, DemoGame.PID, far, far.mods[0], regions).state)
        assertTrue(client.state.value is com.ps4smartcheat.core.backend.ConnectionState.Connected)
    }
}
