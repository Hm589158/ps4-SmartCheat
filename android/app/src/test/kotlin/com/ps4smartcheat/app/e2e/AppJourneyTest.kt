package com.ps4smartcheat.app.e2e

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import com.ps4smartcheat.app.MainActivity
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * End-to-end user journey through the real app UI (Robolectric) against the mock ps4debug-NG server over TCP.
 * Every step saves a screenshot to app/build/outputs/roborazzi/ (uploaded by CI) so the UI can be reviewed.
 *
 * This proves the app's screens, view models, networking and core logic work together. It is NOT a PS4 test.
 */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppJourneyTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()

    /** On failure: whole-screen capture (works even when Compose is not idle) + last step in the log. */
    @get:Rule(order = 1) val watcher = object : TestWatcher() {
        override fun failed(e: Throwable, d: Description) {
            println("E2E FAILED in ${d.methodName} after step '$lastStep': ${e.javaClass.simpleName}: ${e.message?.take(300)}")
            runCatching { File(OUT).mkdirs(); captureScreenRoboImage("$OUT/FAILED_${d.methodName}.png") }
                .onFailure { println("E2E failure screenshot failed: $it") }
        }
    }

    private var lastStep = "start"

    private fun step(name: String) {
        lastStep = name
        println("E2E step: $name")
    }

    /** Screen capture that does not wait for Compose to be idle. */
    private fun screen(name: String) {
        runCatching { File(OUT).mkdirs(); captureScreenRoboImage("$OUT/$name.png") }
    }

    private lateinit var server: MockPs4DebugServer

    @Before fun startServer() {
        server = MockPs4DebugServer()
        server.faults.enableNg(turbo = true)
        DemoGame.install(server)
    }

    @After fun stopServer() = server.close()

    private fun shot(name: String) {
        compose.waitForIdle()
        File(OUT).mkdirs()
        compose.onRoot().captureRoboImage("$OUT/$name.png")
    }

    private fun waitText(text: String, timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitTag(tag: String, timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) { exists(tag) }
    }

    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun tab(label: String) = compose.onAllNodesWithText(label)[0].performClick()

    private fun openSettings() = compose.onNodeWithContentDescription("Settings").performClick()

    private fun waitWatchValue(label: String, value: String, timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(hasTestTag("watchValue-$label") and hasText(value), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitMock(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("mock condition not reached")
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(20)
        }
    }

    @Test fun fullUserJourney() {
        val coins = Address.format(DemoGame.COINS_ADDRESS)
        shot("01_connection_empty")

        // 1. Connect.
        step("connect")
        tag("host").performTextInput("127.0.0.1")
        tag("port").performTextClearance()
        tag("port").performTextInput(server.port.toString())
        tag("connect").performClick()
        waitText("ps4debug-NG (primary)")
        waitText("granted")
        shot("02_connected_ng")

        // 2. Pick the game process.
        step("pick process")
        tab("Processes")
        waitTag("process-eboot.bin")
        waitText("MOCK00001") // title ID shown, game auto-detected
        tag("processSearch").performTextInput("eboot")
        waitTag("process-eboot.bin")
        shot("03_processes")
        tag("process-eboot.bin").performClick()
        waitText("Selected: eboot.bin")
        waitText("MOCK00001")
        shot("04_process_selected")

        // 3. First scan for the coin value shown in game.
        step("first scan")
        tab("Scanner")
        waitTag("scanValue")
        tag("scanValue").performTextInput("5000")
        tag("firstScan").performClick()
        waitText("Scan 1: ")
        waitText("Turbo Scan (PS4)")
        shot("05_first_scan")

        // 4. The game spends coins; refine.
        step("refine")
        server.pokeInt32(DemoGame.PID, DemoGame.COINS_ADDRESS, 4200)
        tag("scanValue").performTextClearance()
        tag("scanValue").performTextInput("4200")
        tag("nextScan").performClick()
        waitText("Scan 2: ")
        compose.onNodeWithTag("scannerList").performScrollToNode(hasTestTag("result-$coins"))
        waitTag("result-$coins")
        waitText("eboot.bin+0x120") // region shown on the result row
        shot("06_refined")

        // 4b. Keep the address in the game's address list.
        step("add to address list")
        tag("add-$coins").performClick()
        waitText("to the address list")

        // 5. Editor is read-only by default.
        step("editor read-only")
        tag("result-$coins").performClick()
        waitText("Read-only mode")
        waitText("4200")
        shot("07_editor_readonly")

        // 6. Enable editing in Settings (with confirmation).
        step("enable editing")
        openSettings()
        waitTag("editingSwitch")
        tag("editingSwitch").performClick()
        waitTag("confirm")
        shot("08_enable_editing_dialog")
        tag("confirm").performClick()

        // 7. Back to the result, write a new value, confirm, verify on the "console".
        step("write")
        tab("Scanner")
        compose.waitUntil(20_000) { exists("scannerList") || exists("newValue") }
        if (!exists("newValue")) {
            // Tab navigation showed the scanner (not the restored editor): open the result again.
            compose.onNodeWithTag("scannerList").performScrollToNode(hasTestTag("result-$coins"))
            tag("result-$coins").performClick()
        }
        waitTag("newValue")
        tag("newValue").performTextInput("99999")
        tag("write").performScrollTo().performClick()
        waitTag("confirm")
        shot("09_write_confirmation")
        tag("confirm").performClick()
        waitText("Write verified")
        waitMock { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 99999 }
        shot("10_write_verified")

        // 8. Restore the original value.
        step("restore")
        tag("restore").performScrollTo().performClick()
        waitMock { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 4200 }
        assertEquals(4200, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))

        // 9. Save as a cheat.
        step("save cheat: type value")
        tag("newValue").performTextClearance()
        tag("newValue").performTextInput("99999")
        step("save cheat: open form")
        tag("saveCheat").performScrollTo().performClick()
        repeat(5) { compose.mainClock.advanceTimeBy(100); Thread.sleep(50) }
        screen("10b_after_save_click_raw")
        step("save cheat: fill form")
        waitTag("cheatName")
        tag("cheatName").performScrollTo().performTextInput("Infinite coins")
        shot("11_save_cheat_form")
        tag("saveConfirm").performScrollTo().performClick()
        waitText("Saved \"Infinite coins\"")

        // 10. Memory viewer around the address.
        step("memory viewer")
        tag("viewMemory").performScrollTo().performClick()
        waitText("byte(s) changed")
        waitText("Int32 4200") // typed value at the target address (restored original)
        shot("12_memory_viewer")

        // 11. Cheat library shows the saved cheat.
        step("cheat library")
        tab("Cheats")
        waitText("Infinite coins")
        waitText("version not set")
        waitText("Validate first")
        shot("13_cheat_library")

        // 11b. The address list shows the saved address with its live value.
        step("address list")
        tab("Addresses")
        waitWatchValue(COINS_LABEL, "4200")
        shot("13b_address_list")

        // 12. Settings overview.
        step("settings")
        openSettings()
        waitTag("editingSwitch")
        shot("14_settings")
    }

    private fun connect() {
        tag("host").performTextInput("127.0.0.1")
        tag("port").performTextClearance()
        tag("port").performTextInput(server.port.toString())
        tag("connect").performClick()
        waitText("ps4debug-NG (primary)")
    }

    @Test fun runningGameIsAutoSelectedAndExitIsDetected() {
        connect()
        // Auto-selection by title ID right after connecting.
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        tab("Scanner")
        waitTag("scanValue")
        tag("scanValue").performTextInput("5000")
        tag("firstScan").performClick()
        waitText("Scan 1: ")
        // The game closes: the session must end and scanning must be blocked.
        server.killProcess(DemoGame.PID)
        waitText("exited", timeoutMs = 20_000)
        waitText("Scanning is disabled until the game runs again")
        waitText("Scan session closed") // informational, not shown as a failure
        shot("30_game_exited")
        // It starts again under a new pid: picked up automatically, old results not reused.
        server.addProcess(MockPs4DebugServer.Process(123, "eboot.bin", titleId = "MOCK00001", path = "/app0/eboot.bin").also {
            it.region("eboot.bin", DemoGame.DATA_START, 0x10000, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
        })
        waitText("eboot.bin (123)", timeoutMs = 20_000)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scan 1: ", substring = true).fetchSemanticsNodes().isEmpty() }
        // The "session closed" notice belongs to the old pid and must not linger.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scan session closed", substring = true).fetchSemanticsNodes().isEmpty() }
        shot("31_game_restarted")
    }

    @Test fun addressListFollowsGameRestart() {
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        tab("Scanner")
        waitTag("scanValue")
        tag("scanValue").performTextInput("5000")
        tag("firstScan").performClick()
        waitText("Scan 1: ")
        val coins = Address.format(DemoGame.COINS_ADDRESS)
        compose.onNodeWithTag("scannerList").performScrollToNode(hasTestTag("add-$coins"))
        tag("add-$coins").performClick()
        waitText("to the address list")
        tab("Addresses")
        waitWatchValue(COINS_LABEL, "5000")
        // Adding again does not duplicate the entry.
        tab("Scanner")
        compose.onNodeWithTag("scannerList").performScrollToNode(hasTestTag("add-$coins"))
        tag("add-$coins").performClick()
        waitText("Already in the address list")
        // The game is closed and started again (new pid): the saved entry resolves in the new process.
        val restarted = server.restartProcess(DemoGame.PID, 123)
        server.pokeInt32(restarted.pid, DemoGame.COINS_ADDRESS, 777)
        waitText("eboot.bin (123)", timeoutMs = 20_000)
        tab("Addresses")
        waitWatchValue(COINS_LABEL, "777")
        waitText("module-relative")
        assertEquals(1, compose.onAllNodesWithTag("watch-$COINS_LABEL", useUnmergedTree = true).fetchSemanticsNodes().size)
        shot("33_address_list_after_restart")
    }

    @Test fun droppedConnectionIsRecoveredAutomatically() {
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        server.dropAllClients()
        tab("Scanner")
        waitTag("scanValue")
        tag("scanValue").performTextInput("5000")
        tag("firstScan").performClick()
        waitText("Scan 1: ")
        waitText("1 result")
        shot("32_after_reconnect")
    }

    @Test fun connectionErrorIsShownAndRecoverable() {
        val dead = java.net.ServerSocket(0).use { it.localPort }
        tag("host").performTextInput("127.0.0.1")
        tag("port").performTextClearance()
        tag("port").performTextInput(dead.toString())
        tag("connect").performClick()
        waitText("refused")
        shot("20_connection_refused")
        tag("port").performTextClearance()
        tag("port").performTextInput(server.port.toString())
        tag("connect").performClick()
        waitText("ps4debug-NG (primary)")
    }

    @Test fun nonLocalAddressIsBlocked() {
        tag("host").performTextInput("8.8.8.8")
        tag("connect").performClick()
        waitText("not a local-network address")
        shot("21_public_address_blocked")
    }

    companion object {
        const val OUT = "build/outputs/roborazzi"
        /** Default name of the coins address: offset from the lowest eboot.bin mapping (text at 0x400000). */
        const val COINS_LABEL = "eboot.bin+0x100120"
    }
}
