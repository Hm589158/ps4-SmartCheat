package com.ps4smartcheat.core.process

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process list can never keep the Processes screen loading: names first, details with games first, a time limit,
 * and a stop when a details request hangs and ends the session. Simulated server only.
 */
class ProcessListingTest {
    private val server = MockPs4DebugServer().also {
        DemoGame.install(it)
        // A few more system processes, read last.
        (50..59).forEach { pid -> it.addProcess(MockPs4DebugServer.Process(pid, "SceDaemon$pid")) }
    }
    private val client = Ps4DebugClient()

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private fun connect(readTimeoutMs: Int = 3000, autoReconnect: Boolean = true) = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = readTimeoutMs, autoReconnect = autoReconnect))
    }

    @Test fun namesComeFirstThenDetailsWithTheGameFirst() = runBlocking {
        connect()
        val updates = ArrayList<List<ProcessDetails>>()
        val list = client.listProcessDetails { updates += it }
        // The first update has every process by name, before any details request.
        assertEquals(list.map { it.pid }, updates.first().map { it.pid })
        assertTrue(updates.first().all { it.info == null })
        // The second update already has the game (games and other programs are read before system processes).
        assertEquals(ProcessKind.GAME, updates[1].single { it.pid == DemoGame.PID }.kind)
        assertEquals(DemoGame.PID, GameDetector.pickGame(list)!!.pid)
        assertTrue(list.all { it.info != null })
    }

    @Test fun aSlowServerIsCutOffByTheTimeLimit() = runBlocking {
        connect()
        server.faults.procInfoDelayMs = 150
        val started = System.currentTimeMillis()
        val list = client.listProcessDetails(budgetMs = 400)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 3000)
        assertEquals(ProcessKind.GAME, list.single { it.pid == DemoGame.PID }.kind) // read first
        assertTrue(list.any { it.reason == "details not read (time limit)" })
        assertEquals(DemoGame.PID, GameDetector.pickGame(list)!!.pid)
    }

    @Test fun aHangingDetailsRequestEndsTheListingInsteadOfLoadingForever() = runBlocking {
        connect(readTimeoutMs = 400, autoReconnect = false)
        server.faults.stallProcInfoPid = 50
        val started = System.currentTimeMillis()
        val list = client.listProcessDetails()
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 5000)
        assertEquals(ProcessKind.GAME, list.single { it.pid == DemoGame.PID }.kind)
        assertTrue(list.single { it.pid == 50 }.info == null)
        // Without auto-reconnect the session ends: the rest is marked instead of waiting.
        assertTrue(list.filter { it.pid > 50 }.all { it.info != null || it.reason == "details not read (connection lost)" })
    }
}
