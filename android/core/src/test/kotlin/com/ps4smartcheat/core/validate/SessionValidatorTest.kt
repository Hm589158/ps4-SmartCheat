package com.ps4smartcheat.core.validate

import com.ps4smartcheat.core.asm.CodePatchPlanner
import com.ps4smartcheat.core.asm.X86
import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.cheat.CheatEntry
import com.ps4smartcheat.core.cheat.CheatProfile
import com.ps4smartcheat.core.cheat.ModuleRef
import com.ps4smartcheat.core.diag.Activity
import com.ps4smartcheat.core.diag.ErrorAdvisor
import com.ps4smartcheat.core.diag.ProcessActivity
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.pointer.CandidateVerdict
import com.ps4smartcheat.core.pointer.PointerCandidate
import com.ps4smartcheat.core.pointer.PointerCandidateStore
import com.ps4smartcheat.core.pointer.PointerChain
import com.ps4smartcheat.core.pointer.PointerStability
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Hex
import com.ps4smartcheat.core.watch.WatchEntry
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Simulates closing and restarting (or updating) the game, then checks what still holds. */
class SessionValidatorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockPs4DebugServer().also { DemoGame.install(it) }
    private val client = Ps4DebugClient()

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private val heapObject = DemoGame.HEAP_START + 0x1000
    /** Static pointer slot in eboot.bin's data that points to a heap object; health lives at +0x10. */
    private val staticSlot = DemoGame.DATA_START + 0x400

    private fun pokeLong(pid: Int, a: Long, v: Long) = server.poke(pid, a, java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(v).array())

    private fun patchEntry(window: ByteArray, regions: List<com.ps4smartcheat.core.model.MemoryRegion>): CheatEntry {
        val p = CodePatchPlanner.plan(DemoGame.SUB_ONE, window, byteArrayOf(0x90.toByte()), "nop", regions.regionFor(DemoGame.SUB_ONE))
        return CheatEntry("p", "No coin loss", ValueType.INT8, p.address, ModuleRef("eboot.bin", 0x2006), originalBytes = Hex.encode(p.off), patchBytes = Hex.encode(p.on), signature = p.signature)
    }

    @Test fun afterRestartReferencesAreReportedValidOrBroken() = runBlocking {
        pokeLong(DemoGame.PID, staticSlot, heapObject)
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        var regions = client.getMemoryRegions(DemoGame.PID)
        val patch = patchEntry(client.readMemory(DemoGame.PID, DemoGame.SUB_ONE, CodePatchPlanner.WINDOW_BYTES), regions)
        val addresses = listOf(
            WatchEntry("a", "Coins", ValueType.INT32, DemoGame.COINS_ADDRESS, ModuleRef("eboot.bin", 0x100120), DemoGame.PID),
            WatchEntry("b", "Heap value", ValueType.INT32, heapObject + 0x10, null, DemoGame.PID),
            WatchEntry("c", "Health", ValueType.INT32, heapObject + 0x10, null, DemoGame.PID, pointer = PointerChain(ModuleRef("eboot.bin", 0x100400), listOf(0x10))),
        )
        val profile = CheatProfile("p", "Demo", "MOCK00001", "01.00", "eboot.bin", cheats = listOf(patch))

        // Restart: new pid, same layout; the object is gone (pointer slot cleared).
        server.restartProcess(DemoGame.PID, 99)
        pokeLong(99, staticSlot, 0)
        regions = client.getMemoryRegions(99)
        val r = SessionValidator.validate(client, 99, regions, addresses, profile, "eboot.bin")
        val byName = r.items.associateBy { it.name }
        assertEquals(Validity.VALID, byName["Coins"]!!.validity)
        assertEquals(Validity.UNVERIFIED, byName["Heap value"]!!.validity)
        assertEquals(Validity.INVALID, byName["Health"]!!.validity)
        assertEquals(Validity.VALID, byName["No coin loss"]!!.validity)

        // The object exists again → the pointer resolves.
        pokeLong(99, staticSlot, heapObject + 0x2000)
        val r2 = SessionValidator.validate(client, 99, regions, addresses.takeLast(1))
        assertEquals(Validity.VALID, r2.items.single().validity)
        assertEquals(heapObject + 0x2010, r2.items.single().address)
    }

    @Test fun updatedGameCodeIsFoundAgainBySignature() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(DemoGame.PID)
        val patch = patchEntry(client.readMemory(DemoGame.PID, DemoGame.SUB_ONE, CodePatchPlanner.WINDOW_BYTES), regions)
        // "Game update": the routine moved 0x100 bytes further and its RIP-relative displacements changed.
        val moved = DemoGame.SPEND_ROUTINE + 0x100
        val code = DemoGame.SPEND_CODE.copyOf()
        code[3] = 0xE0.toByte(); code[12] = 0xE0.toByte() // displacements now 0x100 smaller
        server.poke(DemoGame.PID, moved, code)
        server.poke(DemoGame.PID, DemoGame.SPEND_ROUTINE, ByteArray(16) { 0xCC.toByte() })
        val profile = CheatProfile("p", "Demo", "MOCK00001", "01.02", "eboot.bin", cheats = listOf(patch))
        val item = SessionValidator.validate(client, DemoGame.PID, regions, profile = profile).items.single()
        assertEquals(Validity.MOVED, item.validity)
        assertEquals(0x2106L, item.suggestedOffset)
        // Patched state is recognised as such.
        server.poke(DemoGame.PID, DemoGame.SUB_ONE, byteArrayOf(0x90.toByte(), 0x90.toByte(), 0x90.toByte()))
        server.poke(DemoGame.PID, DemoGame.SPEND_ROUTINE, DemoGame.SPEND_CODE.copyOf(6))
        assertEquals(Validity.ACTIVE, SessionValidator.validate(client, DemoGame.PID, regions, profile = profile).items.single().validity)
        assertTrue(X86.signature(X86.decode(DemoGame.SPEND_CODE, 0)).contains("??"))
    }

    @Test fun pointerCandidatesAreRankedByHowOftenTheySurviveRestarts() = runBlocking {
        // Two good chains reach the object through different static slots; one bad chain points elsewhere.
        val good1 = PointerChain(ModuleRef("eboot.bin", 0x100400), listOf(0x10))
        val good2 = PointerChain(ModuleRef("eboot.bin", 0x100408), listOf(0x10))
        val bad = PointerChain(ModuleRef("eboot.bin", 0x100410), listOf(0x10))
        fun layout(pid: Int, obj: Long, junk: Long) {
            pokeLong(pid, staticSlot, obj); pokeLong(pid, staticSlot + 8, obj); pokeLong(pid, staticSlot + 0x10, junk)
        }
        layout(DemoGame.PID, heapObject, heapObject)
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        var candidates = PointerStability.merge(emptyList(), listOf(bad, good1, good2))
        var pid = DemoGame.PID
        for ((i, junk) in listOf(heapObject + 0x500, 0L, heapObject + 0x900).withIndex()) {
            val next = 200 + i
            server.restartProcess(pid, next); pid = next
            layout(pid, heapObject + 0x3000 * (i + 1), junk)
            val regions = client.getMemoryRegions(pid)
            val report = PointerStability.validate(client, pid, candidates, regions, 4)
            assertTrue(report.referenceIsConsensus)
            assertEquals(heapObject + 0x3000 * (i + 1) + 0x10, report.reference)
            assertEquals(CandidateVerdict.PASS, report.checks.first { it.candidate.chain == good1 }.verdict)
            candidates = report.updated
            // Validating the same session again does not inflate the score.
            assertEquals(candidates.map { it.passes }, PointerStability.validate(client, pid, candidates, regions, 4).updated.map { it.passes })
        }
        assertEquals(setOf(good1, good2), candidates.take(2).map { it.chain }.toSet())
        assertEquals(3, candidates.first().passes)
        val worst = candidates.last()
        assertEquals(bad, worst.chain)
        assertEquals(3, worst.checks)
        assertEquals(0, worst.passes)

        // A user-confirmed address overrides the consensus.
        val regions = client.getMemoryRegions(pid)
        val confirmed = PointerStability.validate(client, pid, candidates, regions, 4, confirmed = heapObject + 0x900 + 0x10)
        assertEquals(CandidateVerdict.PASS, confirmed.checks.first { it.candidate.chain == bad }.verdict)

        val store = PointerCandidateStore(tmp.root)
        store.save("MOCK00001|eboot.bin", "Health (int32)", candidates)
        assertEquals("Health (int32)" to candidates, store.load("MOCK00001|eboot.bin"))
        assertEquals(PointerCandidate(good1), PointerStability.merge(emptyList(), listOf(good1)).single())
    }

    @Test fun activityProbeTellsRunningFromPaused() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(DemoGame.PID)
        assertEquals(Activity.STILL, ProcessActivity.probe(client, DemoGame.PID, regions, intervalMs = 50))
        val ticker = launch(kotlinx.coroutines.Dispatchers.Default) {
            var t = 0
            while (true) { server.pokeInt32(DemoGame.PID, DemoGame.HEAP_START + 8, t++); kotlinx.coroutines.delay(5) }
        }
        assertEquals(Activity.RUNNING, ProcessActivity.probe(client, DemoGame.PID, regions, intervalMs = 100))
        ticker.cancel()
    }

    @Test fun errorAdvisorGivesActionableHints() {
        assertEquals("Connection refused", ErrorAdvisor.diagnose(RuntimeException("x", ConnectException("refused"))).title)
        assertTrue(ErrorAdvisor.diagnose(SocketTimeoutException()).actions.any { it.contains("same network") })
    }
}
