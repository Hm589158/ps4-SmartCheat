package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.logging.Event
import com.ps4smartcheat.core.logging.EventSink
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The game must never stay paused because of the watchpoint feature: every stop is resumed with a verified
 * CONTINUE, and the session is released (and verified) on every way out — capture once, flood, refused or stalled
 * CONTINUE, dropped connections, cancellation while the game is paused.
 */
class WatchRobustnessTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockPs4DebugServer().also { DemoGame.install(it); it.faults.enableNg() }
    private val client = Ps4DebugClient()
    private val log = CopyOnWriteArrayList<Event>()
    private val sink = EventSink { log += it }

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private fun connect() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000, reconnectBackoffMs = 50))
    }

    private suspend fun attach(): DebugSession =
        DebugSession.attach(client, DemoGame.PID, InterruptListener.open(0, InetAddress.getLoopbackAddress(), legacyPort = null), events = sink)

    /** Game thread: runs the demo routine [times] times once the watchpoint is armed. Each paused access waits up to 30 s. */
    private fun game(times: Int, pauseMs: Long = 5): Thread = thread(isDaemon = true) {
        val until = System.currentTimeMillis() + 10_000
        while (server.watchpoints().getOrNull(0) == null && System.currentTimeMillis() < until) Thread.sleep(5)
        repeat(times) {
            server.gameAccess(DemoGame.PID, DemoGame.LOAD_COINS, 6, DemoGame.COINS_ADDRESS, 4, write = false, timeoutMs = 30_000)
            server.gameAccess(DemoGame.PID, DemoGame.STORE_COINS, 6, DemoGame.COINS_ADDRESS, 4, write = true, timeoutMs = 30_000)
            Thread.sleep(pauseMs)
        }
    }

    private fun assertGameRuns(t: Thread, withinMs: Long = 10_000) {
        t.join(withinMs)
        assertFalse("the game thread is still paused", t.isAlive)
        assertFalse(server.gamePaused)
        assertFalse(server.debugAttached)
    }

    @Test fun captureOnceRecordsTheFirstWriterThenReleasesEverything() = runBlocking {
        connect()
        val session = attach()
        val g = game(3)
        val r = withTimeout(20_000) {
            AccessMonitor(client, events = sink).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES, captureOnce = true))
        }
        assertEquals(StopKind.CAPTURED, r.stopKind)
        assertEquals(1, r.hits)
        val rec = r.records.single()
        assertEquals(DemoGame.STORE_COINS, rec.instructionAddress)
        assertNotNull(rec.registers["rax"])
        assertTrue(r.release.confirmed)
        assertGameRuns(g) // the remaining two runs of the routine were not stopped any more
        assertTrue(log.any { it.message == "debugger released" })
    }

    @Test fun refusedContinueIsRetriedOnce() = runBlocking {
        server.faults.continueFailures = 1
        connect()
        val session = attach()
        val g = game(2)
        val r = withTimeout(20_000) { AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES, maxHits = 2)) }
        assertEquals(2, r.hits)
        assertTrue(r.release.confirmed)
        assertGameRuns(g)
        assertTrue(log.any { it.message == "debugger continue refused" })
    }

    @Test fun continueThatKeepsFailingEndsTheSessionAndTheGameRunsAgain() = runBlocking {
        server.faults.continueFailures = 1_000
        connect()
        val session = attach()
        val g = game(1)
        val r = withTimeout(20_000) { AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES)) }
        assertEquals(StopKind.RESUME_FAILED, r.stopKind)
        assertTrue(r.release.confirmed) // DETACH (PT_DETACH) released the paused game
        assertGameRuns(g)
    }

    @Test fun stalledContinueTimesOutAndTheSessionIsReleasedFromANewConnection() = runBlocking {
        connect()
        val session = attach()
        server.faults.stallOnCommand = 0xBDBB0010.toInt() // CONTINUE never answered; the PS4 keeps the session
        val g = game(1)
        val r = withTimeout(30_000) { AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES)) }
        server.faults.stallOnCommand = null
        assertEquals(StopKind.SESSION_LOST, r.stopKind)
        assertTrue(r.release.notes.toString(), r.release.confirmed)
        assertTrue(r.release.recoveredFromNewConnection)
        assertGameRuns(g)
    }

    @Test fun droppedConnectionThePs4DidNotNoticeIsRecovered() = runBlocking {
        server.faults.keepDebugOnDisconnect = true // e.g. Wi-Fi loss without a TCP reset
        connect()
        val session = attach()
        server.faults.dropOnCommand = 0xBDBB0010.toInt() // the connection drops while the game is paused
        val g = game(1)
        val r = withTimeout(30_000) { AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES)) }
        server.faults.dropOnCommand = null
        assertEquals(StopKind.SESSION_LOST, r.stopKind)
        assertTrue(r.release.recoveredFromNewConnection)
        assertGameRuns(g)
    }

    @Test fun cancellingWhileTheGameIsPausedStillResumesAndDetaches() = runBlocking {
        server.faults.continueDelayMs = 800
        connect()
        val session = attach()
        val job = async(Dispatchers.Default) { AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES)) }
        val g = game(1)
        withTimeout(10_000) { while (!server.gamePaused) delay(5) }
        delay(200) // CONTINUE is in flight (delayed by the "PS4")
        job.cancel()
        runCatching { job.await() }
        server.faults.continueDelayMs = 0
        assertGameRuns(g)
        assertFalse(session.alive)
    }

    @Test fun floodGuardStopsBeforeTheGameStalls() = runBlocking {
        connect()
        val session = attach()
        val g = thread(isDaemon = true) {
            val until = System.currentTimeMillis() + 10_000
            while (server.watchpoints().getOrNull(0) == null && System.currentTimeMillis() < until) Thread.sleep(5)
            // A value written as fast as the stops are resumed (every frame, several threads…).
            repeat(500) { server.gameAccess(DemoGame.PID, DemoGame.STORE_COINS, 6, DemoGame.COINS_ADDRESS, 4, write = true, timeoutMs = 30_000) }
        }
        val r = withTimeout(30_000) {
            AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES, maxHitsPerSecond = 10))
        }
        assertEquals(StopKind.FLOOD, r.stopKind)
        assertTrue(r.hits in 10..20)
        assertTrue(r.stopReason.contains("Capture once"))
        assertGameRuns(g)
    }

    @Test fun releaseReportsWhenThePs4CannotBeReached() = runBlocking {
        server.faults.keepDebugOnDisconnect = true
        connect()
        val session = attach()
        server.close() // console gone
        val report = withTimeout(30_000) { session.release(timeoutMs = 10_000) }
        assertFalse(report.confirmed)
        assertTrue(report.notes.toString(), report.notes.any { it.contains("could not reach the PS4") || it.contains("did not finish") })
        assertEquals(report, session.release()) // idempotent
    }

    @Test fun resultsAreSavedAndCanBeOpenedLaterWithoutTheDebugger() = runBlocking {
        connect()
        val regions = client.getMemoryRegions(DemoGame.PID)
        val session = attach()
        val g = game(1)
        val r = withTimeout(20_000) { AccessMonitor(client).run(session, DemoGame.COINS_ADDRESS, 4, MonitorOptions(WatchType.WRITES, captureOnce = true)) }
        g.join(5000)
        val store = WatchResultStore(tmp.root.resolve("watch.json"))
        val saved = SavedWatchSession(
            "s1", "MOCK00001|eboot.bin", "eboot.bin", DemoGame.PID, DemoGame.COINS_ADDRESS, 4, WatchType.WRITES, true,
            1, 2, r.hits, r.stopReason, r.release.confirmed, r.records.map { WatchResults.toSaved(it, regions) },
        )
        store.add(saved)
        val back = WatchResultStore(tmp.root.resolve("watch.json")).load().single()
        assertEquals(saved, back)
        val a = back.records.single()
        assertEquals("mov [0x500120], eax", a.text)
        assertEquals(0x2009L, a.module!!.offset)
        // After a restart (new pid, same layout) the instruction is found through its module offset.
        server.restartProcess(DemoGame.PID, 321)
        assertEquals(DemoGame.STORE_COINS, a.resolve(client.getMemoryRegions(321)))
        for (i in 2..40) store.add(saved.copy(id = "s$i"))
        assertEquals(30, store.load().size)
        assertEquals("s40", store.load().first().id)
        assertEquals(ValueType.INT32.size, back.size)
    }
}
