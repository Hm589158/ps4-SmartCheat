package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Execution breakpoints, single steps and register traces against the mock. The mock game really executes
 * GAME_LOOP → `call rax` → UPDATE → `call SPEND_ROUTINE` with a small interpreter, and the mock debugger behaves like
 * ps4debug-NG's interrupt handler: an execution breakpoint is stepped over by the server and reported with the
 * registers from before the instruction; CMD_DEBUG_STEP_THREAD reports the next instruction.
 */
class ExecutionDebuggerTest {
    private val server = MockPs4DebugServer().also { DemoGame.install(it); it.faults.enableNg() }
    private val client = Ps4DebugClient()
    private val coins get() = server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS)

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private suspend fun attach(): DebugSession {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        return DebugSession.attach(client, DemoGame.PID, InterruptListener.open(0, InetAddress.getLoopbackAddress(), legacyPort = null))
    }

    /** The game runs its loop [times] times once a breakpoint is armed. */
    private fun game(times: Int = 1) = thread(isDaemon = true) {
        val until = System.currentTimeMillis() + 5000
        while (server.watchpoints().none { it != null } && System.currentTimeMillis() < until) Thread.sleep(5)
        repeat(times) { DemoGame.spendCoin(server); Thread.sleep(5) }
    }

    @Test fun breakpointCapturesRegistersAndCallersOnceThenTheGameRunsOn() = runBlocking {
        val session = attach()
        val dbg = ExecutionDebugger(client, session)
        val g = game(2)
        val (hit, end) = withTimeout(20_000) { dbg.awaitHit(DemoGame.SUB_ONE, 10_000) }
        assertEquals(TraceEnd.COMPLETED, end)
        hit!!
        assertEquals(DemoGame.SUB_ONE, hit.address)
        assertEquals("sub eax, 1", hit.instruction!!.text)
        assertEquals(5000L, hit.registers["rax"]) // before the instruction ran
        assertEquals("GameMain", hit.threadName)
        // Callers: UPDATE called the routine (direct call), GAME_LOOP called UPDATE (call rax, on the rbp chain).
        assertEquals(listOf("call 0x402000", "call rax"), hit.callStack!!.map { it.callText })
        assertEquals(FrameConfidence.CONFIRMED, hit.callStack!!.first().confidence)
        // The breakpoint is gone at the first hit and the game is paused after the instruction.
        assertTrue(server.watchpoints().all { it == null })
        assertTrue(dbg.paused && server.gamePaused)
        assertEquals(listOf(DemoGame.SUB_ONE, DemoGame.STORE_COINS), dbg.recorded.map { it.address })
        assertEquals(4999L, dbg.recorded[1].registers["rax"]) // state after the breakpoint instruction (GETREGS)
        assertEquals(listOf("rax"), dbg.recorded[1].changed)

        assertTrue(dbg.resume())
        val report = session.release()
        assertTrue(report.confirmed)
        g.join(5000)
        assertFalse(g.isAlive) // the second pass was not stopped any more
        assertEquals(4998, coins)
        assertFalse(server.debugAttached)
    }

    @Test fun singleStepsReportEachInstruction() = runBlocking {
        val session = attach()
        val dbg = ExecutionDebugger(client, session)
        val g = game()
        assertNotNull(withTimeout(20_000) { dbg.awaitHit(DemoGame.SPEND_ROUTINE, 10_000) }.first)
        // Hit at the load; the server already ran it, so the thread is at "sub eax, 1".
        assertEquals(DemoGame.SUB_ONE, dbg.recorded.last().address)
        val (s1, e1) = dbg.step()
        assertEquals(TraceEnd.COMPLETED, e1)
        assertEquals(DemoGame.STORE_COINS, s1!!.address)
        assertEquals("mov [0x500120], eax", s1.instruction!!.text)
        assertEquals(4999L, s1.registers["rax"])
        assertEquals(listOf("rax"), s1.changed)
        val (s2, _) = dbg.step()
        assertEquals(DemoGame.SPEND_ROUTINE + 15, s2!!.address) // ret
        assertTrue(s2.changed.isEmpty())
        assertEquals(4999, coins) // the store ran
        val (s3, _) = dbg.step()
        assertEquals(DemoGame.UPDATE_RETURN, s3!!.address) // back in the caller
        assertEquals(listOf("rsp"), s3.changed)
        assertEquals(3, server.steps.get())
        assertEquals(listOf(0, 1, 2, 3, 4), dbg.recorded.map { it.index })
        session.release()
        g.join(5000)
        assertFalse(g.isAlive)
    }

    @Test fun traceStepsOverCallsAndEndsWhenTheFunctionReturns() = runBlocking {
        val session = attach()
        val dbg = ExecutionDebugger(client, session)
        val g = game()
        assertNotNull(withTimeout(20_000) { dbg.awaitHit(DemoGame.UPDATE, 10_000) }.first) // push rbp
        val seen = ArrayList<Int>()
        val r = dbg.trace(TraceOptions(maxSteps = 50, stepOverCalls = true, stayInFunction = true)) { seen += it.size }
        assertEquals(TraceEnd.LEFT_FUNCTION, r.end)
        // push rbp · mov rbp, rsp · call (stepped over) · pop rbp · ret · back in GAME_LOOP
        assertEquals(
            listOf(DemoGame.UPDATE, DemoGame.UPDATE + 1, DemoGame.UPDATE + 4, DemoGame.UPDATE_RETURN, DemoGame.UPDATE + 10, DemoGame.GAME_LOOP_RETURN),
            r.steps.map { it.address },
        )
        assertEquals(listOf("push", "mov", "call", "pop", "ret", "ret"), r.steps.map { it.instruction!!.mnemonic })
        assertTrue(r.steps[3].afterCall)
        assertEquals(listOf("rax"), r.steps[3].changed) // the called routine changed eax (4999); rsp is balanced
        assertEquals(4999L, r.steps[3].registers["rax"])
        assertEquals(4999, coins) // the routine ran while being stepped over
        assertTrue(seen.isNotEmpty())
        assertTrue(server.watchpoints().all { it == null }) // the temporary breakpoint is gone
        val text = TraceFormat.format(r.steps) { if (it == DemoGame.UPDATE) "eboot.bin+0x3000" else "" }
        assertTrue(text, text.lines().first().startsWith("#0  0x000403000  eboot.bin+0x3000  push rbp"))
        assertTrue(text, text.lines()[3].contains("(after call)") && text.lines()[3].contains("rax=0x0000000000001387 (4999)"))
        assertTrue(session.release().confirmed)
        g.join(5000)
        assertFalse(g.isAlive)
    }

    @Test fun traceIntoCallsIsLimitedByTheStepCount() = runBlocking {
        val session = attach()
        val dbg = ExecutionDebugger(client, session)
        val g = game()
        assertNotNull(withTimeout(20_000) { dbg.awaitHit(DemoGame.UPDATE + 1, 10_000) }.first) // mov rbp, rsp
        val r = dbg.trace(TraceOptions(maxSteps = 3, stepOverCalls = false, stayInFunction = false))
        assertEquals(TraceEnd.COMPLETED, r.end)
        // mov rbp,rsp (hit) · call · [into the routine] load · sub · store
        assertEquals(
            listOf(DemoGame.UPDATE + 1, DemoGame.UPDATE + 4, DemoGame.SPEND_ROUTINE, DemoGame.SUB_ONE, DemoGame.STORE_COINS),
            r.steps.map { it.address },
        )
        assertTrue(dbg.paused)
        session.release()
        g.join(5000)
        assertFalse(g.isAlive)
        assertEquals(4999, coins)
    }

    @Test fun waitingEndsWithoutAHitAndStepFailuresAreReported() = runBlocking {
        val session = attach()
        val dbg = ExecutionDebugger(client, session)
        // Nobody runs the game: the wait ends, nothing is paused.
        val (none, end) = dbg.awaitHit(DemoGame.SUB_ONE, timeoutMs = 400)
        assertNull(none)
        assertEquals(TraceEnd.NOT_HIT, end)
        assertFalse(dbg.paused)
        assertEquals(TraceEnd.ERROR, dbg.step().second) // not paused: nothing to step
        assertTrue(session.release().confirmed)
        assertTrue(server.watchpoints().all { it == null })

        // PT_STEP refused: reported, the game is still paused and gets resumed by the release.
        val s2 = DebugSession.attach(client, DemoGame.PID, InterruptListener.open(0, InetAddress.getLoopbackAddress(), legacyPort = null))
        val d2 = ExecutionDebugger(client, s2)
        val g = game()
        assertNotNull(withTimeout(20_000) { d2.awaitHit(DemoGame.SUB_ONE, 10_000) }.first)
        server.faults.stepFails = true
        assertEquals(TraceEnd.STEP_REFUSED, d2.step().second)
        assertTrue(server.gamePaused)
        assertTrue(s2.release().confirmed)
        g.join(5000)
        assertFalse(g.isAlive)
        // Data addresses are refused before anything is armed.
        val s3 = DebugSession.attach(client, DemoGame.PID, InterruptListener.open(0, InetAddress.getLoopbackAddress(), legacyPort = null))
        try { ExecutionDebugger(client, s3).awaitHit(DemoGame.COINS_ADDRESS, 100); org.junit.Assert.fail() } catch (_: IllegalArgumentException) {}
        assertTrue(s3.release().confirmed)
    }
}
