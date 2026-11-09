package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.cheat.CheatEntry
import com.ps4smartcheat.core.cheat.CheatJson
import com.ps4smartcheat.core.cheat.CheatLibraryFile
import com.ps4smartcheat.core.cheat.CheatProfile
import com.ps4smartcheat.core.cheat.CodeCheats
import com.ps4smartcheat.core.cheat.GoldHenExporter
import com.ps4smartcheat.core.cheat.GoldHenImport
import com.ps4smartcheat.core.cheat.ModuleRef
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.cheat.PatchState
import com.ps4smartcheat.core.edit.EditDeniedException
import com.ps4smartcheat.core.edit.EditPolicy
import com.ps4smartcheat.core.edit.MemoryEditor
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Hex
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Assembly ON/OFF cheat builder end to end against the mock: assemble on the "PS4", plan a patch that replaces
 * whole instructions, toggle it with verified writes, watch the demo game's behaviour change, save it, export it as
 * GoldHEN JSON and switch it from the library.
 */
class CodePatchTest {
    private val server = MockPs4DebugServer().also { DemoGame.install(it); it.faults.enableNg() }
    private val client = Ps4DebugClient()
    private val tools = CodeTools(client)
    private val codePolicy = EditPolicy(editingEnabled = true, allowNonWritableRegions = true)

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private fun connect() = runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000)) }

    @Test fun assemblesOnTheServerAndReportsKeystoneErrorsThenKeepsWorking() = runBlocking {
        connect()
        val a = tools.assemble("nop\nmov eax, 999", DemoGame.SUB_ONE)
        assertEquals("90B8E7030000", Hex.encode(a.bytes))
        assertEquals(2, a.instructionCount)
        // Branches are encoded for the target address.
        assertEquals("E9F5FFFFFF", Hex.encode(tools.assemble("jmp 0x401FFA", DemoGame.SPEND_ROUTINE).bytes))
        try {
            tools.assemble("frobnicate eax", DemoGame.SUB_ONE); fail()
        } catch (e: AssemblerException) {
            assertTrue(e.detail.contains("MNEMONIC"))
        }
        // The server closed the connection after the error; the next request reconnects transparently.
        assertEquals(1, tools.assemble("ret", 0).instructionCount)
    }

    @Test fun findsCodeReferencingAStaticAddress() = runBlocking {
        connect()
        val text = client.getMemoryRegions(DemoGame.PID).first { it.isExecutable }
        val refs = tools.findReferences(DemoGame.PID, text.start, text.size, DemoGame.COINS_ADDRESS)
        assertTrue(refs.containsAll(listOf(DemoGame.LOAD_COINS, DemoGame.STORE_COINS)))
    }

    @Test fun unavailableOnOlderPayloadsAndClassicServers() = runBlocking {
        server.faults.codeTools = false
        connect()
        try { tools.assemble("nop", 0); fail() } catch (e: CodeToolUnavailableException) { assertTrue(e.message!!.contains("v1.3.4")) }
        client.disconnect()
        server.faults.brand = null
        connect()
        try { tools.assemble("nop", 0); fail() } catch (e: CodeToolUnavailableException) { assertTrue(e.message!!.contains("ps4debug-NG")) }
    }

    @Test fun planCoversWholeInstructionsAndPadsWithNops() = runBlocking {
        connect()
        val regions = client.getMemoryRegions(DemoGame.PID)
        val region = regions.regionFor(DemoGame.SUB_ONE)
        val window = client.readMemory(DemoGame.PID, DemoGame.SUB_ONE, CodePatchPlanner.WINDOW_BYTES)
        // "nop" (1 byte) replaces the whole 3-byte `sub eax, 1`.
        val p = CodePatchPlanner.plan(DemoGame.SUB_ONE, window, tools.assemble("nop", DemoGame.SUB_ONE).bytes, "nop", region)
        assertEquals("83E801", Hex.encode(p.off))
        assertEquals("909090", Hex.encode(p.on))
        assertEquals(listOf("sub eax, 1"), p.replaced.map { it.text })
        assertTrue(p.warnings.any { it.contains("NOP padding") })
        // 5 bytes (mov eax, 999) cover `sub` and the 6-byte store → 9 bytes, warned.
        val big = CodePatchPlanner.plan(DemoGame.SUB_ONE, window, tools.assemble("mov eax, 999", DemoGame.SUB_ONE).bytes, null, region)
        assertEquals(9, big.length)
        assertTrue(big.warnings.any { it.contains("Replaces 2 instructions") })
        // Data memory and mid-instruction starts are refused.
        try { CodePatchPlanner.plan(DemoGame.COINS_ADDRESS, ByteArray(80), byteArrayOf(0x90.toByte()), null, regions.regionFor(DemoGame.COINS_ADDRESS)); fail() } catch (_: CodePatchException) {}
        try { CodePatchPlanner.plan(DemoGame.SUB_ONE, ByteArray(80) { 0x06 }, byteArrayOf(0x90.toByte()), null, region); fail() } catch (_: CodePatchException) {}
        try { CodePatchPlanner.plan(DemoGame.SUB_ONE, window, ByteArray(65) { 0x90.toByte() }, null, region); fail() } catch (_: CodePatchException) {}
    }

    @Test fun onOffChangesTheGamesBehaviourAndIsVerified() = runBlocking {
        connect()
        val regions = client.getMemoryRegions(DemoGame.PID)
        val window = client.readMemory(DemoGame.PID, DemoGame.SUB_ONE, CodePatchPlanner.WINDOW_BYTES)
        val patch = CodePatchPlanner.plan(DemoGame.SUB_ONE, window, tools.assemble("nop", DemoGame.SUB_ONE).bytes, "nop", regions.regionFor(DemoGame.SUB_ONE))

        // Code is not writable: the default policy refuses, the code-patch policy allows with a warning.
        try { MemoryEditor(client, { EditPolicy(editingEnabled = true) }).planBytes(DemoGame.PID, "eboot.bin", patch.address, patch.on); fail() } catch (_: EditDeniedException) {}
        val editor = MemoryEditor(client, { codePolicy })

        DemoGame.spendCoin(server)
        assertEquals(4999, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))

        val on = editor.planBytes(DemoGame.PID, "eboot.bin", patch.address, patch.on)
        assertTrue(on.warnings.any { it.contains("not writable") })
        assertEquals(PatchState.OFF, patch.stateOf(on.original))
        assertTrue(editor.execute(on) is WriteOutcome.Verified)
        assertEquals(PatchState.ON, patch.stateOf(client.readMemory(DemoGame.PID, patch.address, patch.length)))
        repeat(3) { DemoGame.spendCoin(server) }
        assertEquals(4999, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS)) // the patch is really in effect

        // OFF = undo of the verified write restores the original instructions.
        assertTrue(editor.undo() is WriteOutcome.Verified)
        assertArrayEquals(patch.off, server.peek(DemoGame.PID, patch.address, patch.length))
        DemoGame.spendCoin(server)
        assertEquals(4998, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
    }

    @Test fun savedPatchRoundTripsExportsAsGoldHenAndTogglesFromTheLibrary() = runBlocking {
        connect()
        val regions = client.getMemoryRegions(DemoGame.PID)
        val window = client.readMemory(DemoGame.PID, DemoGame.SUB_ONE, CodePatchPlanner.WINDOW_BYTES)
        val patch = CodePatchPlanner.plan(DemoGame.SUB_ONE, window, tools.assemble("nop", DemoGame.SUB_ONE).bytes, "nop", regions.regionFor(DemoGame.SUB_ONE))
        val module = ModuleResolver.refFor(patch.address, regions)!!
        assertEquals(ModuleRef("eboot.bin", 0x2006), module)
        val entry = CheatEntry(
            "c1", "Coins never decrease", ValueType.INT8, patch.address, module, originalBytes = Hex.encode(patch.off),
            patchBytes = Hex.encode(patch.on), assembly = "nop", group = "Infinite coins", signature = patch.signature,
        )
        val value = CheatEntry("c2", "Coins 9999", ValueType.INT32, DemoGame.COINS_ADDRESS, ModuleRef("eboot.bin", 0x100120), "9999", "88130000", group = "Infinite coins")
        val profile = CheatProfile("p", "Demo", "MOCK00001", "01.00", "eboot.bin", cheats = listOf(entry, value))

        // Library file round trip keeps the patch fields and validates them.
        val decoded = CheatJson.decode(CheatJson.encode(CheatLibraryFile(profiles = listOf(profile))))
        assertEquals(entry, decoded.profiles.single().cheats.first())

        // GoldHEN: one mod named after the group with both memory entries.
        val r = GoldHenExporter.export(profile, "Demo", listOf("me"), runningTitleId = "MOCK00001")
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val gh = GoldHenImport.parse(r.json!!)
        val mod = gh.mods.single()
        assertEquals("Infinite coins", mod.name)
        assertEquals(listOf(0x2006L, 0x100120L), mod.patches.map { it.offset })
        assertEquals("909090", Hex.encode(mod.patches[0].on))
        assertEquals("83E801", Hex.encode(mod.patches[0].off))
        // Wrong running game → refused.
        assertTrue(GoldHenExporter.export(profile, "Demo", emptyList(), runningTitleId = "CUSA00001").errors.single().contains("running game"))

        // Library patches are switched with the same checks as a GoldHEN file.
        val file = CodeCheats.asGoldHenFile(profile)!!
        val c = GoldHenImport.check(client, DemoGame.PID, file, file.mods.single(), regions)
        assertTrue(c.canEnable)
        assertEquals(DemoGame.SUB_ONE, c.addresses.single())
        val editor = MemoryEditor(client, { codePolicy })
        for ((i, p) in c.mod.patches.withIndex()) assertTrue(editor.execute(editor.planBytes(DemoGame.PID, "eboot.bin", c.addresses[i], p.on)) is WriteOutcome.Verified)
        assertTrue(GoldHenImport.check(client, DemoGame.PID, file, file.mods.single(), regions).canDisable)
    }

    @Test fun groupWithAnUnexportableMemberIsSkippedWhole() {
        val ok = CheatEntry("a", "A", ValueType.INT32, 0, ModuleRef("eboot.bin", 0x10), "1", "00000000", group = "G")
        val heap = CheatEntry("b", "B", ValueType.INT32, 0x200000000, null, "1", "00000000", group = "G")
        val solo = CheatEntry("c", "C", ValueType.INT32, 0, ModuleRef("eboot.bin", 0x20), "2", "00000000")
        val r = GoldHenExporter.export(CheatProfile("p", "P", "CUSA12345", "01.00", "eboot.bin", cheats = listOf(ok, heap, solo)), "Game", emptyList())
        assertNotNull(r.json)
        assertEquals(1, r.exported)
        assertTrue(r.skipped.single().startsWith("Group \"G\" skipped"))
        val bad = GoldHenExporter.export(CheatProfile("p", "P", "CUSA1234", "1.5", "eboot.bin", cheats = listOf(solo)), "Game", emptyList())
        assertEquals(2, bad.errors.size)
    }
}
