package com.ps4smartcheat.core.process

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.backend.ConnectionMonitor
import com.ps4smartcheat.core.model.ProcessEntry
import com.ps4smartcheat.core.model.ProcessInfo
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ScanController
import com.ps4smartcheat.core.scan.ScanMode
import com.ps4smartcheat.core.scan.ScanStatus
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProcessLifecycleTest {
    private fun info(pid: Int, name: String, title: String, path: String) = ProcessInfo(pid, name, path, title, "")

    @Test fun classifiesByTitleIdPathAndName() {
        fun k(name: String, title: String = "", path: String = "", withInfo: Boolean = true) =
            GameDetector.classify(ProcessEntry(1, name), if (withInfo) info(1, name, title, path) else null).kind
        assertEquals(ProcessKind.GAME, k("eboot.bin", "CUSA12345", "/app0/eboot.bin"))
        assertEquals(ProcessKind.GAME, k("default.elf", "CUSA00419", "/mnt/sandbox/pfsmnt/CUSA00419-app0/default.elf"))
        assertEquals(ProcessKind.GAME, k("guest.elf", "PLAS10001"))
        assertEquals(ProcessKind.GAME, k("homebrew.elf", "", "/app0/homebrew.elf")) // no title id, app0 path
        assertEquals(ProcessKind.SYSTEM, k("SceShellUI", "NPXS20001"))
        assertEquals(ProcessKind.SYSTEM, k("eboot.bin", "NPXS20113", "/system/vsh/app/NPXS20113/eboot.bin")) // system app named eboot.bin
        assertEquals(ProcessKind.SYSTEM, k("mini-syscore.elf"))
        assertEquals(ProcessKind.OTHER, k("daemon.elf", withInfo = false))
    }

    @Test fun picksGameOrNothingWhenAmbiguous() {
        fun d(pid: Int, name: String, title: String, path: String = "/app0/$name") =
            GameDetector.classify(ProcessEntry(pid, name), info(pid, name, title, path))
        val sys = d(40, "SceShellUI", "NPXS20001")
        assertNull(GameDetector.pickGame(listOf(sys)))
        assertEquals(77, GameDetector.pickGame(listOf(sys, d(77, "default.elf", "CUSA00001")))!!.pid)
        // Same title with two processes: the main executable wins.
        assertEquals(80, GameDetector.pickGame(listOf(d(79, "default_mp.elf", "CUSA00001"), d(80, "eboot.bin", "CUSA00001")))!!.pid)
        // Two different games: ambiguous.
        assertNull(GameDetector.pickGame(listOf(d(77, "eboot.bin", "CUSA00001"), d(78, "eboot.bin", "CUSA00002"))))
    }

    @Test fun identityRecognisesRestartsButNotPidReuse() {
        val a = ProcessIdentity(77, "eboot.bin", "CUSA00001", "/app0/eboot.bin")
        assertTrue(a.sameProgram(a.copy(pid = 90)))
        assertFalse(a.sameProgram(ProcessIdentity(77, "eboot.bin", "CUSA00002", "/app0/eboot.bin")))
    }

    // ------------------------------------------------------------------ against the mock server

    private lateinit var server: MockPs4DebugServer
    private lateinit var client: Ps4DebugClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        server = MockPs4DebugServer()
        DemoGame.install(server)
        client = Ps4DebugClient()
        runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, firmwareProbeTimeoutMs = 300, reconnectBackoffMs = 50)) }
    }

    @After fun tearDown() {
        scope.cancel()
        runBlocking { client.disconnect() }
        server.close()
    }

    @Test fun detectsTheRunningGameOnTheConsole() = runBlocking {
        val list = client.listProcessDetails()
        assertEquals(ProcessKind.SYSTEM, list.first { it.name == "SceShellUI" }.kind)
        val game = GameDetector.pickGame(list)!!
        assertEquals(DemoGame.PID, game.pid)
        assertEquals("MOCK00001", game.titleId)
    }

    @Test fun watcherReportsExitAndRestart() = runBlocking {
        val game = GameDetector.pickGame(client.listProcessDetails())!!
        val watcher = ProcessWatcher(client, scope)
        val first = async { withTimeout(5000) { watcher.processEvents.first() } }
        delay(50)
        server.killProcess(DemoGame.PID)
        watcher.check(game.identity)
        assertTrue(first.await() is ProcessEvent.Exited)

        val second = async { withTimeout(5000) { watcher.processEvents.first() } }
        delay(50)
        server.addProcess(MockPs4DebugServer.Process(123, "eboot.bin", titleId = "MOCK00001", path = "/app0/eboot.bin"))
        watcher.check(game.identity)
        val ev = second.await()
        assertTrue(ev.toString(), ev is ProcessEvent.Restarted && ev.replacement.pid == 123)
        assertEquals(123, watcher.current!!.pid)
    }

    @Test fun reusedPidWithOtherProgramCountsAsExit() = runBlocking {
        val game = GameDetector.pickGame(client.listProcessDetails())!!
        val watcher = ProcessWatcher(client, scope)
        val ev = async { withTimeout(5000) { watcher.processEvents.first() } }
        delay(50)
        server.killProcess(DemoGame.PID)
        server.addProcess(MockPs4DebugServer.Process(DemoGame.PID, "eboot.bin", titleId = "OTHR00002"))
        watcher.check(game.identity)
        assertTrue(ev.await() is ProcessEvent.Exited)
    }

    @Test fun backgroundWatcherNoticesExit() = runBlocking {
        val game = GameDetector.pickGame(client.listProcessDetails())!!
        val watcher = ProcessWatcher(client, scope, intervalMs = 100)
        val ev = async { withTimeout(5000) { watcher.processEvents.first() } }
        watcher.watch(game.identity)
        server.killProcess(DemoGame.PID)
        assertTrue(ev.await() is ProcessEvent.Exited)
        watcher.stop()
    }

    @Test fun staleScanSessionIsInvalidated() = runBlocking {
        val controller = ScanController(client, scope)
        controller.firstScan(DemoGame.PID, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000")
        withTimeout(30_000) { controller.status.first { it is ScanStatus.Completed } }
        assertTrue(controller.hasSession(DemoGame.PID))
        controller.invalidate(DemoGame.PID, "game exited")
        assertFalse(controller.hasSession(DemoGame.PID))
        assertNull(controller.session.value)
        val st = controller.status.value
        assertTrue(st.toString(), st is ScanStatus.Ended && st.reason.contains("game exited"))
        controller.activate(999)
        assertEquals(ScanStatus.Idle, controller.status.value)
    }

    @Test fun monitorRepairsSilentlyDroppedLink() = runBlocking {
        val monitor = ConnectionMonitor(client, scope, intervalMs = 150)
        monitor.start()
        server.dropAllClients()
        withTimeout(5000) { while (client.reconnectCount.get() == 0) delay(50) }
        monitor.stop()
        assertEquals(3, client.getProcesses().size)
    }
}
