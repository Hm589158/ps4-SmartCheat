package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.cheat.CheatProfile
import com.ps4smartcheat.core.cheat.CheatRepository
import com.ps4smartcheat.core.cheat.CodeCheats
import com.ps4smartcheat.core.cheat.GoldHenExporter
import com.ps4smartcheat.core.cheat.GoldHenImport
import com.ps4smartcheat.core.cheat.InjectionEntries
import com.ps4smartcheat.core.cheat.PatchState
import com.ps4smartcheat.core.edit.EditDeniedException
import com.ps4smartcheat.core.edit.EditPolicy
import com.ps4smartcheat.core.edit.MemoryEditor
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.util.Hex
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Code caves / Assembly injection against the mock. The mock "game" runs its routine with a small x86 interpreter
 * (MiniCpu), so the tests observe the injected code really running: the jump, the new code, the moved original
 * instructions (RIP-relative store re-encoded for the cave) and the jump back.
 */
class CodeInjectionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockPs4DebugServer().also { DemoGame.install(it); it.faults.enableNg() }
    private val client = Ps4DebugClient()
    private val tools = CodeTools(client)
    private val alloc = CaveAllocator(client, client)
    private val editor = MemoryEditor(client, { EditPolicy(editingEnabled = true, allowNonWritableRegions = true) })
    private val writer = InjectionWriter(client, editor)
    private val pid = DemoGame.PID
    private val coins get() = server.peekInt32(pid, DemoGame.COINS_ADDRESS)

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private fun connect() = runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000)) }

    private suspend fun moduleCave(hook: Long): CaveSpace {
        val regions = client.getMemoryRegions(pid)
        val c = CaveFinder.find(client, pid, regions, hook).first()
        return CaveSpace(c.address, c.size, CaveKind.MODULE_PADDING, client.readMemory(pid, c.address, c.size), c.atSegmentEnd)
    }

    private suspend fun plan(hook: Long, cave: CaveSpace, source: String, original: OriginalCode = OriginalCode.AFTER): CodeInjection {
        val regions = client.getMemoryRegions(pid)
        val window = client.readMemory(pid, hook, InjectionPlanner.HOOK_WINDOW)
        return InjectionPlanner.plan(hook, window, regions.regionFor(hook), cave, original, source, true) { at -> tools.assemble(source, at).bytes }
    }

    private suspend fun switch(inj: CodeInjection, on: Boolean): InjectionResult =
        writer.execute(pid, inj, writer.prepare(pid, "eboot.bin", inj, on), on)

    // ------------------------------------------------------------------ cave finder

    @Test fun scanFindsPaddingButNotShortZeroRunsInsideCode() {
        val code = ByteArray(0x200) { 0x90.toByte() }
        for (i in 0x10 until 0x24) code[i] = 0 // 20 zeros inside code: probably an immediate or data, not offered
        for (i in 0x40 until 0x90) code[i] = 0xCC.toByte() // int3 padding
        for (i in 0x100 until 0x200) code[i] = 0 // zeros up to the end of the mapping
        val runs = CaveFinder.scan(code, 0x1000, 0x1200)
        assertEquals(listOf(0x1050L to 0x3F, 0x1110L to 0xF0), runs)
    }

    @Test fun finderRanksPagePaddingFirstAndSkipsReservedCaves() = runBlocking {
        connect()
        val regions = client.getMemoryRegions(pid)
        val list = CaveFinder.find(client, pid, regions, DemoGame.SUB_ONE)
        val tail = list.first()
        assertTrue(tail.atSegmentEnd)
        assertEquals(DemoGame.TEXT_START + 0x10000, tail.end)
        assertEquals("eboot.bin", tail.module!!.name)
        val int3 = list.single { it.fill == 0xCC.toByte() }
        assertEquals(DemoGame.SPEND_ROUTINE + 0x20, int3.address) // 8-byte lead margin, 16-byte aligned
        assertFalse(int3.atSegmentEnd)
        // A cave used by a saved injection is not offered again.
        val again = CaveFinder.find(client, pid, regions, DemoGame.SUB_ONE, reserved = listOf(tail.address until tail.address + 64))
        assertTrue(again.none { it.address == tail.address })
    }

    // ------------------------------------------------------------------ planner (no server)

    @Test fun plannerRefusesUnsafeHooksAndCaves() = runBlocking {
        val region = MemoryRegion("executable", 0x1000, 0x2000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_EXEC)
        val caveBytes = ByteArray(256)
        val cave = CaveSpace(0x1E00, 256, CaveKind.MODULE_PADDING, caveBytes)
        val nop: suspend (Long) -> ByteArray = { byteArrayOf(0x90.toByte()) }
        // sub eax,1 ; sub eax,1 ; jne 0x1003 (back into the middle of the 6 bytes the jump overwrites)
        val loop = Hex.decode("83E801 83E801 75FB C3".replace(" ", "")) + ByteArray(100)
        try { InjectionPlanner.plan(0x1000, loop, region, cave, OriginalCode.AFTER, "nop", true, nop); fail() } catch (e: CodePatchException) {
            assertTrue(e.message!!, e.message!!.contains("jumps into the middle"))
        }
        val plain = Hex.decode("83E801 83E801 C3".replace(" ", "")) + ByteArray(100)
        // Cave too small.
        try { InjectionPlanner.plan(0x1000, plain, region, CaveSpace(0x1E00, 4, CaveKind.MODULE_PADDING, ByteArray(4)), OriginalCode.AFTER, "nop", true, nop); fail() } catch (_: CodePatchException) {}
        // Cave no longer padding.
        try { InjectionPlanner.plan(0x1000, plain, region, CaveSpace(0x1E00, 256, CaveKind.MODULE_PADDING, ByteArray(256) { 0x48 }), OriginalCode.AFTER, "nop", true, nop); fail() } catch (_: CodePatchException) {}
        // Not executable.
        try { InjectionPlanner.plan(0x1000, plain, region.copy(prot = MemoryRegion.PROT_READ), cave, OriginalCode.AFTER, "nop", true, nop); fail() } catch (_: CodePatchException) {}
        // Cave overlapping the hook.
        try { InjectionPlanner.plan(0x1000, plain, region, CaveSpace(0x1002, 256, CaveKind.MODULE_PADDING, caveBytes), OriginalCode.AFTER, "nop", true, nop); fail() } catch (_: CodePatchException) {}
        // A valid one: 6 bytes stolen (two whole instructions), jmp rel32 + 1 NOP.
        val ok = InjectionPlanner.plan(0x1000, plain, region, cave, OriginalCode.AFTER, "nop", true, nop)
        assertEquals(HookJump.REL32, ok.jump)
        assertEquals(2, ok.stolen.size)
        assertEquals(0x1006L, ok.returnAddress)
        assertEquals("E9FB0D000090", Hex.encode(ok.hookOn))
        assertEquals(listOf("nop", "sub", "sub", "jmp"), ok.caveListing.map { it.mnemonic })
        assertEquals(0x1006L, ok.caveListing.last().branchTarget)
        assertTrue(InjectionPlanner.template(ok.stolen, OriginalCode.AFTER).contains("//   sub eax, 1"))
    }

    @Test fun farCaveUsesAbsoluteJumpsAndMovesBranches() = runBlocking {
        val hook = 0x40_0000L
        val region = MemoryRegion("executable", hook, hook + 0x10000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_EXEC)
        // mov eax,1 ; mov eax,2 ; mov eax,3  (15 bytes ≥ 14)
        val window = Hex.decode("B801000000B802000000B803000000") + ByteArray(100) { 0x90.toByte() }
        val far = 0x20_0000_0000L
        val inj = InjectionPlanner.plan(hook, window, region, CaveSpace(far, 256, CaveKind.ALLOCATED, ByteArray(256)), OriginalCode.BEFORE, "nop", true) { byteArrayOf(0x90.toByte()) }
        assertEquals(HookJump.ABS64, inj.jump)
        assertEquals(3, inj.stolen.size)
        assertEquals("FF2500000000" + Hex.encode(com.ps4smartcheat.core.util.LE.bytes64(far)) + "90", Hex.encode(inj.hookOn))
        assertEquals(listOf("mov", "mov", "mov", "nop", "jmp"), inj.caveListing.map { it.mnemonic })
        assertEquals("FF2500000000" + Hex.encode(com.ps4smartcheat.core.util.LE.bytes64(hook + 15)), Hex.encode(inj.caveOn.copyOfRange(16, 30)))
        assertTrue(inj.warnings.any { it.contains("14-byte") })
        assertTrue(inj.warnings.any { it.contains("cannot be saved") })

        // A short conditional branch moved 256 MiB away becomes a near one that still targets the original code.
        val jcc = Hex.decode("7520B801000000") + ByteArray(100) { 0x90.toByte() }
        val near = hook + 0x1000_0000L
        val moved = InjectionPlanner.plan(hook, jcc, region, CaveSpace(near, 256, CaveKind.ALLOCATED, ByteArray(256)), OriginalCode.BEFORE, "nop", true) { byteArrayOf(0x90.toByte()) }
        assertEquals(HookJump.REL32, moved.jump)
        val jne = moved.caveListing.first()
        assertEquals(Flow.CONDITIONAL_JUMP, jne.flow)
        assertEquals(6, jne.length)
        assertEquals(hook + 0x22, jne.branchTarget)
        assertTrue(moved.warnings.any { it.contains("control-flow") })
        // A RIP-relative store moved 128 GiB away: below 4 GiB iced re-encodes it EIP-relative, still the same variable…
        val ripWindow = DemoGame.SPEND_CODE.copyOfRange(6, 16) + Hex.decode("B801000000") + ByteArray(100) { 0x90.toByte() }
        val low = InjectionPlanner.plan(DemoGame.SUB_ONE, ripWindow, region.copy(start = 0x40_0000L), CaveSpace(far, 256, CaveKind.ALLOCATED, ByteArray(256)), OriginalCode.AFTER, "nop", true) { byteArrayOf(0x90.toByte()) }
        assertEquals(DemoGame.COINS_ADDRESS, low.caveListing.single { it.writesMemory }.ripTarget)
        // …but a variable above 4 GiB (as on the PS4) cannot be reached from there: refused, not silently broken.
        val high = 0x9_0000_0000L
        val highRegion = MemoryRegion("executable", high, high + 0x10000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_EXEC)
        val highWindow = Hex.decode("890510000000" + "B801000000" + "B802000000") + ByteArray(100) { 0x90.toByte() }
        try {
            InjectionPlanner.plan(high, highWindow, highRegion, CaveSpace(far, 256, CaveKind.ALLOCATED, ByteArray(256)), OriginalCode.AFTER, "nop", true) { byteArrayOf(0x90.toByte()) }
            fail()
        } catch (e: CodePatchException) {
            assertTrue(e.message!!, e.message!!.contains("cannot be moved"))
        }
    }

    @Test fun stateTellsOnOffHalfAndMismatch() = runBlocking {
        val region = MemoryRegion("executable", 0x1000, 0x2000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_EXEC)
        val inj = InjectionPlanner.plan(0x1000, Hex.decode("83E80183E801C3") + ByteArray(100), region, CaveSpace(0x1E00, 64, CaveKind.MODULE_PADDING, ByteArray(64)), OriginalCode.AFTER, "nop", true) { byteArrayOf(0x90.toByte()) }
        assertEquals(InjectionState.OFF, inj.state(inj.hookOff, inj.caveOff))
        assertEquals(InjectionState.ON, inj.state(inj.hookOn, inj.caveOn))
        assertEquals(InjectionState.CAVE_ONLY, inj.state(inj.hookOff, inj.caveOn))
        assertEquals(InjectionState.MISMATCH, inj.state(inj.hookOn, inj.caveOff))
        assertEquals(InjectionState.MISMATCH, inj.state(ByteArray(6), inj.caveOn))
    }

    // ------------------------------------------------------------------ end to end in the mock game

    @Test fun injectedCodeRunsWithTheMovedOriginalAndIsRemovedCleanly() = runBlocking {
        connect()
        // Hook "sub eax, 1": the jump (5 bytes) also takes "mov [rip+…], eax", which must be re-encoded for the cave.
        val inj = plan(DemoGame.SUB_ONE, moduleCave(DemoGame.SUB_ONE), "mov eax, 1000")
        assertEquals(listOf("sub", "mov"), inj.stolen.map { it.mnemonic })
        assertEquals(DemoGame.STORE_COINS + 6, inj.returnAddress)
        val moved = inj.caveListing.single { it.mnemonic == "mov" && it.writesMemory }
        assertEquals(DemoGame.COINS_ADDRESS, moved.ripTarget) // same variable from the new place
        assertEquals(InjectionState.OFF, writer.state(pid, inj))
        assertTrue(inj.warnings.none { it.contains("zero bytes inside") }) // page padding at the end of the code
        val inner = InjectionPlanner.plan(0x1000, Hex.decode("83E80183E801C3") + ByteArray(100), null, CaveSpace(0x1E00, 64, CaveKind.MODULE_PADDING, ByteArray(64)), OriginalCode.AFTER, "nop", true) { byteArrayOf(0x90.toByte()) }
        assertTrue(inner.warnings.any { it.contains("zero bytes inside") })

        // Read-only by default: nothing can be written.
        val readOnly = InjectionWriter(client, MemoryEditor(client, { EditPolicy() }))
        try { readOnly.prepare(pid, "eboot.bin", inj, true); fail() } catch (_: EditDeniedException) {}

        val steps = writer.prepare(pid, "eboot.bin", inj, true)
        assertEquals(listOf("cave 1/1", "jump"), steps.map { it.label }) // cave first, jump last
        val on = writer.execute(pid, inj, steps, true)
        assertTrue(on.message, on.ok)
        assertEquals(InjectionState.ON, on.stateAfter)

        val trace = DemoGame.spendCoin(server)
        assertEquals(999, coins) // mov eax, 1000 then the original sub 1 and store
        assertTrue(trace.any { it in inj.caveRange })
        assertEquals(DemoGame.SPEND_ROUTINE + 15, trace.last()) // returned to the routine's ret

        // ON twice is refused; OFF restores the jump first, then the padding.
        try { writer.prepare(pid, "eboot.bin", inj, true); fail() } catch (_: EditDeniedException) {}
        val offSteps = writer.prepare(pid, "eboot.bin", inj, false)
        assertEquals(listOf("jump", "cave 1/1"), offSteps.map { it.label })
        assertTrue(writer.execute(pid, inj, offSteps, false).ok)
        assertArrayEquals(inj.hookOff, server.peek(pid, inj.hookAddress, inj.hookOff.size))
        assertArrayEquals(inj.caveOff, server.peek(pid, inj.caveAddress, inj.caveOff.size))
        DemoGame.spendCoin(server)
        assertEquals(998, coins)
    }

    @Test fun replacingTheOriginalSkipsIt() = runBlocking {
        connect()
        val inj = plan(DemoGame.SUB_ONE, moduleCave(DemoGame.SUB_ONE), "nop", OriginalCode.SKIP)
        assertTrue(switch(inj, true).ok)
        repeat(3) { DemoGame.spendCoin(server) }
        assertEquals(5000, coins) // neither the sub nor the store run any more
    }

    @Test fun failedEnableIsUndoneAndNeverLeavesTheJump() = runBlocking {
        connect()
        val inj = plan(DemoGame.SUB_ONE, moduleCave(DemoGame.SUB_ONE), "mov eax, 7")
        val steps = writer.prepare(pid, "eboot.bin", inj, true)
        server.faults.ignoreWrites = true // writes are acknowledged but do not stick
        val r = writer.execute(pid, inj, steps, true)
        server.faults.ignoreWrites = false
        assertFalse(r.ok)
        assertEquals(0, r.written)
        assertEquals(InjectionState.OFF, writer.state(pid, inj))
        DemoGame.spendCoin(server)
        assertEquals(4999, coins)
    }

    @Test fun allocatedCaveIsValidatedUsedAndFreed() = runBlocking {
        connect()
        val before = client.getMemoryRegions(pid)
        val a = alloc.allocateNear(pid, DemoGame.SUB_ONE, 256)
        assertTrue(before.none { it.contains(a.address) })
        assertEquals(DemoGame.TEXT_START + 0x10000, a.address) // first free 16 KiB page above the hint
        val cave = CaveSpace(a.address, a.length, CaveKind.ALLOCATED, client.readMemory(pid, a.address, a.length))
        val inj = plan(DemoGame.SUB_ONE, cave, "mov eax, 50")
        assertEquals(HookJump.REL32, inj.jump)
        assertTrue(switch(inj, true).ok)
        DemoGame.spendCoin(server)
        assertEquals(49, coins)
        // OFF only removes the jump; the allocated code stays until it is freed.
        val off = writer.prepare(pid, "eboot.bin", inj, false)
        assertEquals(listOf("jump"), off.map { it.label })
        assertTrue(writer.execute(pid, inj, off, false).ok)
        assertEquals(InjectionState.CAVE_ONLY, writer.state(pid, inj))
        DemoGame.spendCoin(server)
        assertEquals(48, coins)
        alloc.free(a)
        assertTrue(client.getMemoryRegions(pid).none { it.contains(a.address) })
        // The library refuses session-only injections.
        try { InjectionEntries.build(inj, "x", null, client.getMemoryRegions(pid)) { "id" }; fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun allocationRepliesAreNeverTrusted() = runBlocking {
        connect()
        // ps4debug-NG answers CMD_SUCCESS with the hint when the kernel call fails.
        server.faults.allocFails = true
        for (hint in listOf(DemoGame.SUB_ONE, DemoGame.TEXT_START)) {
            try { alloc.allocateNear(pid, hint, 256); fail() } catch (e: CaveAllocationException) {
                assertTrue(e.message!!, e.message!!.contains("did not allocate"))
            }
        }
        server.faults.allocFails = false
        server.faults.allocProt = 3 // rw- : code could not run there
        try { alloc.allocateNear(pid, DemoGame.SUB_ONE, 256); fail() } catch (e: CaveAllocationException) { assertTrue(e.message!!.contains("not rwx")) }
        server.faults.allocProt = 7
        try { alloc.allocateNear(pid, DemoGame.SUB_ONE, CaveAllocator.MAX_ALLOCATION + 1); fail() } catch (_: CaveAllocationException) {}
        val a = alloc.allocateNear(pid, DemoGame.SUB_ONE, 64)
        server.faults.freeIgnored = true
        try { alloc.free(a); fail() } catch (e: CaveAllocationException) { assertTrue(e.message!!.contains("still mapped")) }
        // The connection is still usable after all of this.
        assertEquals(5000, client.readMemory(pid, DemoGame.COINS_ADDRESS, 4).let { com.ps4smartcheat.core.util.LE.u32(it, 0).toInt() })
    }

    @Test fun allocationNeedsPs4debugNg() = runBlocking {
        server.faults.brand = null
        connect()
        try { alloc.allocateNear(pid, DemoGame.SUB_ONE, 64); fail() } catch (_: CodeToolUnavailableException) {}
        assertEquals(0, server.allocRequests.get())
    }

    // ------------------------------------------------------------------ library, GoldHEN

    @Test fun savedInjectionTogglesFromTheLibraryInOrderAndExportsToGoldHen() = runBlocking {
        connect()
        // 15 instructions of 5 bytes = 75 bytes of new code: the cave is stored as two chunks (≤ 64 bytes each).
        val source = (List(14) { "mov eax, 1" } + "mov eax, 300").joinToString("\n")
        val inj = plan(DemoGame.SUB_ONE, moduleCave(DemoGame.SUB_ONE), source)
        val regions = client.getMemoryRegions(pid)
        var n = 0
        val entries = InjectionEntries.build(inj, "Coins 299", null, regions) { "e${++n}" }
        assertEquals(listOf("Coins 299 · cave 1/2", "Coins 299 · cave 2/2", "Coins 299 · jump"), entries.map { it.name })
        assertTrue(entries.all { it.group == "Coins 299" && it.isPatch })
        assertEquals(source, entries.last().assembly)
        assertNotNull(entries.last().signature)

        // Stored, read back after a "restart".
        val repo = CheatRepository(tmp.root.resolve("cheats.json"))
        repo.load()
        val p = repo.createProfile("Demo", "MOCK00001", "01.00", "eboot.bin")
        entries.forEach { repo.upsertCheat(p.id, it) }
        val stored: CheatProfile = CheatRepository(tmp.root.resolve("cheats.json")).also { it.load() }.profiles.value.single()
        assertEquals(listOf(inj.caveAddress until inj.caveAddress + inj.caveOn.size).map { it.first }, InjectionEntries.reservedCaves(stored, regions).map { it.first }.take(1))

        val file = CodeCheats.asGoldHenFile(stored)!!
        val mod = file.mods.single()
        assertEquals(3, mod.patches.size)
        var check = GoldHenImport.check(client, pid, file, mod, regions)
        assertTrue(check.canEnable)
        assertEquals(listOf(0, 1, 2), check.writeOrder(true))

        // Interrupted toggle: only the first cave chunk written → partial; Enable completes only what is missing.
        val p0 = mod.patches[0]
        assertTrue(editor.execute(editor.planBytes(pid, "eboot.bin", check.addresses[0], p0.on)) is WriteOutcome.Verified)
        check = GoldHenImport.check(client, pid, file, mod, regions)
        assertTrue(check.partial && check.canEnable && check.canDisable)
        assertEquals(listOf(1, 2), check.writeOrder(true))
        for (i in check.writeOrder(true)) {
            assertTrue(editor.execute(editor.planBytes(pid, "eboot.bin", check.addresses[i], mod.patches[i].on)) is WriteOutcome.Verified)
        }
        check = GoldHenImport.check(client, pid, file, mod, regions)
        assertEquals(PatchState.ON, check.state)
        assertEquals(InjectionState.ON, writer.state(pid, inj))
        DemoGame.spendCoin(server)
        assertEquals(299, coins)

        // OFF in reverse: the jump goes first.
        assertEquals(listOf(2, 1, 0), check.writeOrder(false))

        val export = GoldHenExporter.export(stored, "Demo", emptyList())
        assertTrue(export.errors.toString(), export.errors.isEmpty())
        val back = GoldHenImport.parse(export.json!!).mods.single()
        assertEquals(listOf(inj.caveAddress - DemoGame.TEXT_START, inj.caveAddress - DemoGame.TEXT_START + 64, DemoGame.SUB_ONE - DemoGame.TEXT_START), back.patches.map { it.offset })
        assertNull(GoldHenImport.mismatch(GoldHenImport.parse(export.json!!), "MOCK00001", "eboot.bin"))
    }
}
