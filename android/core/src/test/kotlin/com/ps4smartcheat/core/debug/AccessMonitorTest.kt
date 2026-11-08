package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.diag.ErrorAdvisor
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * "Find out what writes / accesses this address" against the mock debugger: listener first, attach (the mock
 * connects back like ps4debug-NG does on port 755), a hardware watchpoint, stops reported on the interrupt
 * channel, resume after each stop, and detach. The demo game's routine provides real instructions to decode.
 */
class AccessMonitorTest {
    private val server = MockPs4DebugServer().also { DemoGame.install(it); it.faults.enableNg() }
    private val client = Ps4DebugClient()

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private fun connect() = runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000)) }

    /**
     * Listener as the app opens it, but on any free port and without the legacy 755 socket. Nothing is configured on
     * the mock: the extended attach request tells it where to connect.
     */
    private fun listener(): InterruptListener = InterruptListener.open(0, InetAddress.getLoopbackAddress(), legacyPort = null)

    /** The game runs its routine [times] times once the watchpoint is in place. */
    private fun gameLoop(times: Int, pauseMs: Long = 5) = thread(isDaemon = true) {
        val until = System.currentTimeMillis() + 5000
        while (server.watchpoints().getOrNull(0) == null && System.currentTimeMillis() < until) Thread.sleep(5)
        repeat(times) { DemoGame.spendCoin(server); Thread.sleep(pauseMs) }
    }

    @Test fun findWhatWritesReportsTheStoreInstructionWithCountAndRegisters() = runBlocking {
        connect()
        val session = DebugSession.attach(client, DemoGame.PID, listener())
        assertTrue(server.debugAttached)
        val game = gameLoop(5)
        val result = withTimeout(20_000) {
            AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES, maxHits = 5))
        }
        game.join(5000)
        assertEquals(5, result.hits)
        val r = result.records.single()
        assertEquals(DemoGame.STORE_COINS, r.instructionAddress)
        assertEquals(DemoGame.STORE_COINS + 6, r.trapRip)
        assertEquals("mov [0x500120], eax", r.instruction!!.text)
        assertEquals(5, r.count)
        assertEquals(4995L, r.registers["rax"]) // 5000 - 5, value written by the last hit
        assertEquals(DemoGame.COINS_ADDRESS, r.effectiveAddress)
        assertEquals("GameMain", r.threadName)
        assertTrue(server.continues.get() >= 5)
        assertFalse("detached at the end", server.debugAttached)
        assertFalse(session.alive)
    }

    @Test fun findWhatAccessesAlsoReportsReads() = runBlocking {
        connect()
        val session = DebugSession.attach(client, DemoGame.PID, listener())
        val game = gameLoop(3)
        val result = withTimeout(20_000) {
            AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.ACCESSES, maxHits = 6))
        }
        game.join(5000)
        assertEquals(setOf(DemoGame.LOAD_COINS, DemoGame.STORE_COINS), result.records.map { it.instructionAddress }.toSet())
        assertTrue(result.records.all { it.count == 3 })
        assertTrue(result.records.first { it.instructionAddress == DemoGame.LOAD_COINS }.instruction!!.readsMemory)
    }

    @Test fun stoppingEarlyDetachesAndTheGameKeepsRunning() = runBlocking {
        connect()
        val session = DebugSession.attach(client, DemoGame.PID, listener())
        val job = async(Dispatchers.Default) {
            AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES, maxHits = 1000))
        }
        val game = gameLoop(2)
        game.join(5000)
        delay(300)
        job.cancel()
        runCatching { job.await() }
        withTimeout(5000) { while (server.debugAttached) delay(20) }
        // Without a debugger the routine runs freely (no stop, nothing blocks).
        val before = server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS)
        DemoGame.spendCoin(server)
        assertEquals(before - 1, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
    }

    @Test fun secondAttachIsRefusedAsBusy() = runBlocking {
        connect()
        val s1 = DebugSession.attach(client, DemoGame.PID, listener())
        val other = Ps4DebugClient()
        try {
            other.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
            try {
                DebugSession.attach(other, DemoGame.PID, listener()); fail()
            } catch (e: DebuggerException) {
                assertTrue(ErrorAdvisor.diagnose(e).title.contains("busy"))
            }
        } finally {
            other.disconnect()
        }
        s1.detach()
        assertFalse(server.debugAttached)
    }

    @Test fun droppedConnectionEndsTheSessionOnThePs4() = runBlocking {
        connect()
        val session = DebugSession.attach(client, DemoGame.PID, listener())
        assertTrue(server.debugAttached)
        server.dropAllClients() // server.c free_client → debug_full_teardown
        withTimeout(5000) { while (server.debugAttached) delay(20) }
        client.getProcesses() // reconnects
        assertFalse(session.alive)
        try { session.resume(); fail() } catch (e: DebuggerException) { assertTrue(e.message!!.contains("ended")) }
        session.close()
    }

    @Test fun extendedAttachAnnouncesTheListenerPort() = runBlocking {
        connect()
        val l = listener()
        assertEquals(755, server.faults.debugPort) // nothing configured on the "PS4"
        val session = DebugSession.attach(client, DemoGame.PID, l)
        assertEquals(l.port, server.lastAttachPort)
        session.detach()
    }

    @Test fun attachBodyMatchesThePullRequestExample() {
        // PROTOCOL.md (PR #1): pid 0x55, client 192.168.1.10, port 7550.
        val body = DebugProtocol.attachBody(0x55, byteArrayOf(192.toByte(), 168.toByte(), 1, 10), 7550)
        assertEquals("55000000C0A8010A7E1D0000", com.ps4smartcheat.core.util.Hex.encode(body))
        assertEquals(DebugProtocol.ATTACH_EX_PACKET_SIZE, body.size)
        try { DebugProtocol.attachBody(1, ByteArray(4), 70000); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun serverRejectsAnOutOfRangePortWithoutAttaching() = runBlocking {
        connect()
        // Raw request as a buggy client would send it: debug_port > 65535 → CMD_ERROR before PT_ATTACH.
        val st = client.exclusive("raw attach") { w ->
            w.send(w.header(DebugProtocol.CMD_DEBUG_ATTACH, 12), com.ps4smartcheat.core.util.LE.bytes32(DemoGame.PID.toLong()) + byteArrayOf(127, 0, 0, 1) + com.ps4smartcheat.core.util.LE.bytes32(70000))
            w.readU32().also { w.invalidate() }
        }
        assertEquals(com.ps4smartcheat.core.protocol.Ps4DebugProtocol.CMD_ERROR, st)
        assertFalse(server.debugAttached)
        assertEquals(null, server.orphanedAttachPid)
    }

    @Test fun unpatchedPayloadFailsWithAClearExplanation() = runBlocking {
        server.faults.attachPortOption = false // ignores debug_port and connects to 755
        server.faults.debugPort = ServerSocket(0).use { it.localPort } // nothing listens there (Android cannot bind 755)
        connect()
        try {
            DebugSession.attach(client, DemoGame.PID, listener()); fail()
        } catch (e: DebuggerException) {
            assertTrue(e.message!!, e.message!!.contains("PR #1"))
            assertTrue(e.message!!.contains("Restart the game"))
            assertEquals("Debugger could not attach", ErrorAdvisor.diagnose(e).title)
        }
        assertFalse(server.debugAttached)
        assertEquals(DemoGame.PID, server.orphanedAttachPid) // what the real server leaves behind
        assertEquals(1, client.getProcesses().count { it.pid == DemoGame.PID }) // connection usable again
    }

    @Test fun unpatchedPayloadWorksWhenTheLegacyPortCanBeOpened() = runBlocking {
        // Desktop / rooted case: the listener also binds the legacy port, so an unpatched payload connects there.
        val legacy = ServerSocket(0).use { it.localPort }
        server.faults.attachPortOption = false
        server.faults.debugPort = legacy
        connect()
        val l = InterruptListener.open(0, InetAddress.getLoopbackAddress(), legacyPort = legacy)
        assertEquals(legacy, l.legacyPort)
        val session = DebugSession.attach(client, DemoGame.PID, l)
        val game = gameLoop(2)
        val r = withTimeout(20_000) { AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES, maxHits = 2)) }
        game.join(5000)
        assertEquals(2, r.hits)
    }

    @Test fun listenerFallsBackToAFreePortWhenThePreferredOneIsTaken() {
        ServerSocket(0).use { busy ->
            InterruptListener.open(busy.localPort, InetAddress.getLoopbackAddress(), legacyPort = null).use { l ->
                assertTrue(l.port != busy.localPort && l.port > 0)
                assertEquals(null, l.legacyPort)
            }
        }
    }

    @Test fun listenerReportsAPortThatCannotBeUsed() {
        ServerSocket(0).use { busy ->
            try {
                InterruptListener.openExactly(busy.localPort, InetAddress.getLoopbackAddress()).close(); fail()
            } catch (e: ListenerUnavailableException) {
                assertEquals("Interrupt connection unavailable", ErrorAdvisor.diagnose(e).title)
            }
        }
    }

    @Test fun watchpointValidationAndProcessResume() = runBlocking {
        connect()
        val session = DebugSession.attach(client, DemoGame.PID, listener())
        try { session.setWatchpoint(0, DemoGame.COINS_ADDRESS + 1, 4, WatchType.WRITES); fail() } catch (_: IllegalArgumentException) {}
        try { session.setWatchpoint(4, DemoGame.COINS_ADDRESS, 4, WatchType.WRITES); fail() } catch (_: IllegalArgumentException) {}
        session.setWatchpoint(1, DemoGame.COINS_ADDRESS, 8, WatchType.ACCESSES)
        val wp = server.watchpoints()[1]!!
        assertEquals(2, wp.lenCode) // DR7 LEN for 8 bytes
        assertEquals(3, wp.breakType)
        session.detach()
        DebugSession.continueProcess(client, DemoGame.PID)
        assertEquals(DemoGame.PID to 0, server.lastProcessStop)
    }

    @Test fun parsesRegistersAndInterruptLayout() {
        val p = ByteArray(DebugProtocol.INTERRUPT_PACKET_SIZE)
        val bb = java.nio.ByteBuffer.wrap(p).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 7).putInt(4, (5 shl 8) or 0x7F)
        "Main".toByteArray().copyInto(p, 8)
        bb.putLong(DebugProtocol.REGS_OFFSET + 14 * 8, 0x1234) // rax is the 15th GPR
        bb.putLong(DebugProtocol.REGS_OFFSET + 136, 0x401000) // rip
        bb.putLong(DebugProtocol.DBREGS_OFFSET + 6 * 8, 0b0100) // DR6: B2
        val ev = InterruptEvent.parse(p)
        assertEquals(7, ev.lwpid)
        assertEquals(DebugProtocol.SIGTRAP, ev.signal)
        assertEquals("Main", ev.threadName)
        assertEquals(0x1234L, ev.registers["rax"])
        assertEquals(0x401000L, ev.registers.rip)
        assertEquals(setOf(2), ev.firedSlots)
        assertEquals(0x4A0, DebugProtocol.DBREGS_OFFSET + DebugProtocol.DBREGS_SIZE)
    }
}
