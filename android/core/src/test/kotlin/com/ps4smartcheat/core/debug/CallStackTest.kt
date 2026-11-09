package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.util.LE
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Call stacks at watchpoint hits. The mock game calls its spend routine through two callers (a direct call from
 * UPDATE, which keeps a frame pointer, and an indirect `call rax` from GAME_LOOP) on a stack in the mock's memory, so
 * the unwinder works on real return addresses read while the game is paused.
 */
class CallStackTest {
    private val server = MockPs4DebugServer().also { DemoGame.install(it); it.faults.enableNg() }
    private val client = Ps4DebugClient()

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    // ------------------------------------------------------------------ unwinder on synthetic memory

    private val code = MemoryRegion("executable", 0x1000, 0x2000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_EXEC)
    private val stackRegion = MemoryRegion("stack", 0x9000, 0xA000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_WRITE)
    private val mem = ByteArray(0x1000).also { m ->
        // 0x1100: call 0x1800 (direct, the function containing the hit) → returns to 0x1105
        byteArrayOf(0xE8.toByte(), 0xFB.toByte(), 0x06, 0, 0).copyInto(m, 0x100)
        // 0x1200: call rax → returns to 0x1202
        byteArrayOf(0xFF.toByte(), 0xD0.toByte()).copyInto(m, 0x200)
        // 0x1300: call 0x1700 (direct, unrelated function) → returns to 0x1305
        byteArrayOf(0xE8.toByte(), 0xFB.toByte(), 0x03, 0, 0).copyInto(m, 0x300)
        // 0x1400: plain nop/mov code, no call before 0x1408
        for (i in 0x400 until 0x408) m[i] = 0x90.toByte()
    }
    private suspend fun read(a: Long, n: Int): ByteArray = mem.copyOfRange((a - 0x1000).toInt(), (a - 0x1000).toInt() + n)

    private fun stack(vararg v: Long) = v.fold(ByteArray(0)) { acc, x -> acc + LE.bytes64(x) }

    @Test fun framesAreConfirmedFromCallsAndClassified() = runBlocking {
        val rip = 0x1820L // inside the function at 0x1800
        val s = stack(0x1105, 0xDEAD, 0x1408, 0x1202, 0x1305, 0x0)
        val frames = CallStack.unwindWithReader(rip, 0x9000, 0, s, listOf(code, stackRegion), ::read)
        assertEquals(listOf(0x1105L, 0x1202L, 0x1305L), frames.map { it.returnAddress })
        assertEquals(listOf(FrameConfidence.CONFIRMED, FrameConfidence.PROBABLE, FrameConfidence.WEAK), frames.map { it.confidence })
        assertEquals(listOf(0, 24, 32), frames.map { it.stackOffset })
        assertEquals("call 0x1800", frames[0].callText)
        assertEquals(0x1100L, frames[0].callAddress)
        assertEquals(0x1800L, frames[0].callTarget)
        assertNull(frames[1].callTarget)
    }

    @Test fun formatsForTheClipboardAndResolvesAfterARestart() {
        val f = StackFrame(0x403009, 0x403004, "call 0x402000", 0x402000, 0, FrameConfidence.CONFIRMED, com.ps4smartcheat.core.cheat.ModuleRef("eboot.bin", 0x3004))
        assertEquals(
            "#0  0x000402009  mov [0x500120], eax  (watchpoint hit)\n#1  0x000403004  call 0x402000  (call verified)",
            CallStack.format(0x402009, "mov [0x500120], eax", listOf(f)),
        )
        val moved = listOf(MemoryRegion("eboot.bin", 0x800000, 0x810000, 0, MemoryRegion.PROT_READ or MemoryRegion.PROT_EXEC))
        assertEquals(0x803004L, f.resolve(moved))
        assertEquals(0x403004L, f.copy(module = null).resolve(moved))
    }

    @Test fun framePointerChainMarksItsSlots() = runBlocking {
        // rbp = rsp+8: [rbp] = next frame pointer (higher), [rbp+8] = return address
        val s = stack(0x0, 0x9020, 0x1305, 0x0, 0x0)
        val frames = CallStack.unwindWithReader(0x1650, 0x9000, 0x9008, s, listOf(code, stackRegion), ::read)
        assertEquals(FrameConfidence.FRAME_CHAIN, frames.single().confidence) // a direct call elsewhere, but on the chain
    }

    @Test fun stackLengthStaysInsideTheMapping() {
        assertEquals(1024, CallStack.readableLength(0x9000, listOf(stackRegion)))
        assertEquals(0x100, CallStack.readableLength(0x9F00, listOf(stackRegion)))
        assertEquals(0, CallStack.readableLength(0x5000, listOf(stackRegion)))
        assertEquals(CallStack.MAX_STACK_BYTES, CallStack.readableLength(0x9000, listOf(stackRegion.copy(end = 0x20000)), 100_000))
    }

    // ------------------------------------------------------------------ at a real (mock) watchpoint hit

    private fun listener() = InterruptListener.open(0, InetAddress.getLoopbackAddress(), legacyPort = null)

    private fun gameLoop(times: Int) = thread(isDaemon = true) {
        val until = System.currentTimeMillis() + 5000
        while (server.watchpoints().getOrNull(0) == null && System.currentTimeMillis() < until) Thread.sleep(5)
        repeat(times) { DemoGame.spendCoin(server); Thread.sleep(5) }
    }

    @Test fun captureOnceRecordsTheCallersAndTheyAreSaved() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val session = DebugSession.attach(client, DemoGame.PID, listener())
        val game = gameLoop(1)
        val result = withTimeout(20_000) {
            AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES, captureOnce = true))
        }
        game.join(5000)
        val r = result.records.single()
        assertEquals(DemoGame.STORE_COINS, r.instructionAddress)
        val frames = r.callStack!!
        assertEquals(listOf(DemoGame.UPDATE_RETURN, DemoGame.GAME_LOOP_RETURN), frames.map { it.returnAddress })
        assertEquals(listOf("call 0x402000", "call rax"), frames.map { it.callText })
        assertEquals(listOf(FrameConfidence.CONFIRMED, FrameConfidence.FRAME_CHAIN), frames.map { it.confidence })
        assertEquals("eboot.bin", frames[0].module!!.name)
        assertEquals(0x3004L, frames[0].module!!.offset)
        assertTrue(r.registers.rsp in DemoGame.STACK_START until DemoGame.STACK_START + DemoGame.STACK_SIZE)

        // Saved with the session and shown in the disassembler.
        val saved = WatchResults.toSaved(r, client.getMemoryRegions(DemoGame.PID))
        val back = Json.decodeFromString(SavedAccess.serializer(), Json.encodeToString(SavedAccess.serializer(), saved))
        assertEquals(frames, back.callStack)
        assertEquals(frames, RegisterSnapshot.of(r).callStack)
        assertEquals(frames, RegisterSnapshot.of(back, r.instructionAddress, 0).callStack)
        // Old saved files without call stacks still load.
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(SavedAccess.serializer(), """{"instructionAddress":"0x402009","count":1,"thread":"T"}""")
        assertTrue(old.callStack.isEmpty())
    }

    @Test fun callStacksCanBeTurnedOff() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val session = DebugSession.attach(client, DemoGame.PID, listener())
        val game = gameLoop(1)
        val result = withTimeout(20_000) {
            AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES, captureOnce = true, stackBytes = 0))
        }
        game.join(5000)
        assertNull(result.records.single().callStack)
        assertTrue(result.release.detached)
    }
}
