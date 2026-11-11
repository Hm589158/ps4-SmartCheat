package com.ps4smartcheat.app.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import com.ps4smartcheat.core.tabs.Tool
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
            runCatching {
                val c = (compose.activity.application as com.ps4smartcheat.app.SmartCheatApp).container
                println("E2E app log (last 40):")
                c.log.lines.value.takeLast(40).forEach { println("  " + it.format()) }
                println("E2E journal: " + c.editor.journal.value.map { "pid ${it.pid} ${Address.format(it.address)} len ${it.original.size}" })
                println("E2E undo: " + c.editor.undoStack.value.map { "${it.label} ${Address.format(it.target.address)}" })
                println("E2E selected: " + c.selectedProcess.value?.let { "${it.name} ${it.pid} exited=${it.exited}" } + " state=" + c.backend.state.value)
                println("E2E pointer scan: " + c.pointerScan.state.value.let { "running=${it.running} chains=${it.chains.size} target=${it.target} error=${it.error}" })
            }.onFailure { println("E2E diagnostics failed: $it") }
            runCatching { File(OUT).mkdirs(); captureScreenRoboImage("$OUT/FAILED_${d.methodName}.png") }
                .onFailure { println("E2E failure screenshot failed: $it") }
        }
    }

    private var lastStep = "start"

    private fun c() = (compose.activity.application as com.ps4smartcheat.app.SmartCheatApp).container

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
        // Screenshots double as README images: let the auto-select notice disappear first (never fails the test).
        runCatching { waitNoSnackbar("Detected game", timeoutMs = 8_000) }
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

    /**
     * Switches to the tab of a tool (by its label, e.g. "Scanner"); a tool without a tab is opened from the new-tab
     * page. The tab strip and the new-tab page are lazy lists: the item is scrolled into view first.
     */
    private fun tab(label: String) {
        val tool = Tool.entries.first { it.label == label }
        val chip = hasTestTag("tabChip-${tool.name}")
        if (runCatching { tag("tabStrip").performScrollToNode(chip) }.isSuccess) {
            compose.onAllNodes(chip)[0].performClick()
        } else {
            tag("newTab").performClick()
            waitTag("toolLauncher")
            tag("toolLauncher").performScrollToNode(hasTestTag("tool-${tool.name}"))
            tag("tool-${tool.name}").performClick()
        }
        compose.waitForIdle()
    }

    private fun chips(tool: Tool) = compose.onAllNodesWithTag("tabChip-${tool.name}").fetchSemanticsNodes().size

    private fun backPress() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Snackbars sit above the bottom of the content; wait until the auto-select notice is gone before tapping there. */
    private fun waitNoSnackbar(text: String = "Detected game", timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * Clicks through the semantics OnClick action (the handler a tap runs) after scrolling the node into view.
     * Used for buttons right after text input, where Robolectric sometimes drops the injected touch.
     */
    private fun press(t: String) {
        tag(t).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Confirms the open ConfirmDialog through its button's semantics click (dialogs are separate windows). */
    private fun confirmDialog() {
        waitTag("confirm")
        tag("confirm").performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun openSettings() = compose.onNodeWithContentDescription("Settings").performClick()

    private fun waitWatchValue(label: String, value: String, timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(hasTestTag("watchValue-$label") and hasText(value), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits for a condition on the mock console while letting the app's main-thread work run. */
    private fun waitMock(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        // waitForIdle drains the main looper: coroutines resuming on Dispatchers.Main (each step of an edit) only
        // run then. compose.waitUntil alone does not idle when the condition never touches Compose semantics.
        while (true) {
            compose.waitForIdle()
            if (cond()) return
            if (System.currentTimeMillis() > end) {
                dumpThreads()
                throw AssertionError("mock condition not reached within $timeoutMs ms")
            }
            Thread.sleep(20)
        }
    }

    /** Stacks of app, mock and coroutine threads while the mock server is still running (diagnoses stalls). */
    private fun dumpThreads() {
        println("E2E thread dump:")
        for ((t, st) in Thread.getAllStackTraces()) {
            val frames = st.take(25)
            if (frames.none { it.className.contains("ps4smartcheat") || it.className.contains("kotlinx.coroutines.sync") }) continue
            println("  thread ${t.name} (${t.state})")
            frames.forEach { println("    at $it") }
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

        // 5. Editing is on by default; in read-only mode (switched off here) the editor refuses writes.
        assertEquals(true, c().settings.value.editingEnabled && c().settings.value.allowNonWritableRegions && c().settings.value.allowSystemProcesses)
        c().settings.update { it.copy(editingEnabled = false) }
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
        confirmDialog()

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
        press("write")
        waitTag("confirm")
        shot("09_write_confirmation")
        confirmDialog()
        waitText("Write verified")
        waitMock { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 99999 }
        shot("10_write_verified")

        // 8. Restore the original value.
        step("restore")
        press("restore")
        waitMock { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 4200 }
        assertEquals(4200, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))

        // 8b. Undo the restore (back to 99999), then redo it (4200 again) — each confirmed.
        step("undo/redo")
        // The memory changes before the app has verified the write and updated its history: wait for the UI.
        waitText("Undo (2)")
        press("undo")
        waitTag("confirm")
        waitText("Will write:")
        shot("10c_undo_confirmation")
        confirmDialog()
        waitMock { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 99999 }
        waitText("Redo (1)")
        press("redo")
        waitTag("confirm")
        confirmDialog()
        waitMock { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 4200 }

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
        press("saveConfirm")
        waitText("Saved \"Infinite coins\"")

        // 9b. Keep the address in the game's address list (editor shows the result inline, no snackbar).
        step("add to address list")
        tag("addAddress").performScrollTo().performClick()
        waitText("to the address list")

        // 10. Memory viewer around the address.
        step("memory viewer")
        tag("viewMemory").performScrollTo().performClick()
        waitText("byte(s) changed")
        waitText("Int32 4200") // typed value at the target address (restored original)
        shot("12_memory_viewer")

        // 10b. Patch raw bytes in the viewer (confirmed + verified), then put 4200 back the same way.
        step("hex patch")
        tag("patchBytes").performTextInput("2A 00 00 00")
        press("patchWrite")
        waitTag("confirm")
        shot("12b_patch_confirmation")
        confirmDialog()
        waitMock { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 42 }
        waitText("written and verified")
        tag("patchBytes").performTextClearance()
        tag("patchBytes").performTextInput("68 10 00 00")
        press("patchWrite")
        waitTag("confirm")
        confirmDialog()
        waitMock { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 4200 }

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
        // The entry becomes a cheat in the library (module-relative, value typed here).
        waitNoSnackbar("Already in the address list")
        waitNoSnackbar()
        tag("watchLive").performClick() // stop live refresh so the form is not recomposed under the test's taps
        compose.onAllNodesWithText(COINS_LABEL)[0].performClick()
        waitTag("saveAsCheat-$COINS_LABEL")
        tag("saveAsCheat-$COINS_LABEL").performScrollTo().performClick()
        waitTag("cheatValue")
        tag("cheatValue").performTextInput("99999")
        press("saveConfirm")
        waitText("eboot.bin relative")
        tab("Cheats")
        waitText(COINS_LABEL)
        shot("33b_cheat_from_address_list")
    }

    @Test fun bytePatternSearchFindsPlantedBytes() {
        val at = DemoGame.DATA_START + 0x400
        server.poke(DemoGame.PID, at, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0x77, 0xEF.toByte(), 0x13, 0x37))
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        tab("Scanner")
        waitTag("openPatternSearch")
        tag("openPatternSearch").performScrollTo().performClick()
        waitTag("patternInput")
        tag("patternInput").performTextInput("DE AD ?? EF 13 37")
        tag("patternSearch").performClick()
        waitTag("patternSummary")
        waitTag("hit-" + Address.format(at))
        waitText("DE AD 77 EF 13 37")
        shot("34_pattern_search")
    }

    @Test fun rangeScanFindsValueBetweenBounds() {
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        tab("Scanner")
        waitTag("scanValue")
        compose.onAllNodesWithText("Exact value")[0].performClick()
        waitText("Value between")
        compose.onAllNodesWithText("Value between")[0].performClick()
        waitTag("scanValue2")
        tag("scanValue").performTextInput("4990")
        tag("scanValue2").performTextInput("5010")
        tag("firstScan").performClick()
        waitText("Scan 1: ")
        val coins = Address.format(DemoGame.COINS_ADDRESS)
        compose.onNodeWithTag("scannerList").performScrollToNode(hasTestTag("result-$coins"))
        waitTag("result-$coins")
        waitText("Chunked scan (Android)") // range modes are not offloaded to the PS4
        shot("35_range_scan")
    }

    @Test fun pointerScanFindsChainThatFollowsMovingObject() {
        // Static eboot.bin data points at a heap "player" object whose field +0x48 holds the value.
        val player = DemoGame.HEAP_START + 0x1000
        val target = player + 0x48
        fun le64(v: Long) = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        server.poke(DemoGame.PID, DemoGame.DATA_START + 0x200, le64(player))
        server.pokeInt32(DemoGame.PID, target, 31337)
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        // Add the (absolute, heap) address to the list, then search pointers to it from the list entry.
        tab("Addresses")
        waitTag("addManual")
        tag("addManual").performClick()
        waitTag("watchAddress")
        tag("watchAddress").performTextInput(Address.format(target))
        tag("watchAdd").performClick()
        val label = Address.format(target)
        waitWatchValue(label, "31337")
        compose.onAllNodesWithText(label)[0].performClick()
        waitTag("pointerScan-$label")
        tag("pointerScan-$label").performScrollTo().performClick()
        waitTag("pointerStart")
        tag("pointerStart").performClick()
        val chain = "[eboot.bin+0x100200]+0x48"
        compose.waitUntil(30_000) { c().pointerScan.state.value.let { !it.running && it.chains.isNotEmpty() } }
        compose.onNodeWithTag("pointerList").performScrollToNode(hasTestTag("chain-$chain"))
        waitTag("chain-$chain", timeoutMs = 30_000)
        shot("36_pointer_scan")
        waitNoSnackbar()
        tag("addChain-$chain").performScrollTo().performClick()
        waitText("Added pointer")
        // The game moves the object: the pointer entry follows it, the absolute entry does not.
        val moved = DemoGame.HEAP_START + 0x2000
        server.pokeInt32(DemoGame.PID, moved + 0x48, 4242)
        server.poke(DemoGame.PID, DemoGame.DATA_START + 0x200, le64(moved))
        tab("Addresses")
        waitWatchValue(chain, "4242")
        waitText("pointer chain")
        shot("37_pointer_entry_follows")
    }

    @Test fun diagnosticsAndGuide() {
        server.faults.linkLatencyMs = 20L
        connect()
        openSettings()
        waitTag("openDiagnostics")
        press("openDiagnostics")
        waitTag("pingTest")
        waitText("ps4debug-NG")
        press("pingTest")
        waitTag("pingResult", timeoutMs = 20_000)
        waitText("avg")
        shot("38_diagnostics")
        compose.onNodeWithContentDescription("Guide").performClick()
        waitTag("help")
        waitText("Find a value")
        shot("39_guide")
    }

    private fun hexBytes(a: Long, n: Int) = server.peek(DemoGame.PID, a, n).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test fun assemblyOnOffPatchChangesTheGameAndIsSaved() {
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        // Code patches need editing + non-writable regions (both confirmed).
        // Editing and code writes are on by default (each write is still confirmed).
        assertEquals(true, c().settings.value.editingEnabled && c().settings.value.allowNonWritableRegions)

        step("disassemble")
        compose.onNodeWithContentDescription("Code").performClick()
        waitTag("codeAddress")
        tag("codeAddress").performTextInput(Address.format(DemoGame.SUB_ONE))
        press("codeGo")
        waitText("sub eax, 1")
        // The rows are below the instruction card in the lazy list: scroll to the store's row before looking for it.
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("codeList").performScrollToNode(hasTestTag("asm_" + java.lang.Long.toHexString(DemoGame.STORE_COINS))) }.isSuccess }
        waitText("mov [0x500120], eax")
        shot("41_disassembly")
        compose.onNodeWithTag("codeList").performScrollToNode(hasTestTag("codeSource"))

        step("preview nop")
        tag("codeSource").performTextClearance()
        tag("codeSource").performTextInput("nop")
        press("codePreview")
        waitText("OFF (original)")
        waitText("ON   90 90 90")
        shot("42_patch_preview")

        step("switch ON")
        press("codeOn")
        confirmDialog()
        waitMock { hexBytes(DemoGame.SUB_ONE, 3) == "90 90 90" }
        waitText("Patch ON")
        // The game's routine no longer subtracts a coin.
        DemoGame.spendCoin(server)
        assertEquals(5000, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
        shot("43_patch_on")

        step("save patch: failure keeps the form and the input")
        press("codeSave")
        waitTag("cheatName")
        tag("cheatName").performTextClearance()
        press("saveConfirm")
        waitTag("saveError")
        waitText("Enter a cheat name")
        assertEquals(true, exists("cheatName")) // still open, nothing saved
        assertEquals(0, c().cheats.profiles.value.size)

        step("save patch: success")
        tag("cheatName").performTextInput("No coin loss")
        press("saveConfirm")
        waitTag("codeSaved")
        waitText("Saved code patch")
        compose.waitUntil(10_000) { !exists("cheatName") } // the form closes only after the save was confirmed
        val savedProfile = c().cheats.profiles.value.single()
        assertEquals("No coin loss", savedProfile.cheats.single().name)
        // Persisted: a fresh repository on the same file (what the next app start reads) finds the patch.
        val reloaded = com.ps4smartcheat.core.cheat.CheatRepository(java.io.File(compose.activity.filesDir, "cheats.json"))
        kotlinx.coroutines.runBlocking { reloaded.load() }
        val stored = reloaded.profiles.value.single().cheats.single()
        assertEquals("909090", stored.patchBytes)
        assertEquals("83E801", stored.originalBytes)
        assertEquals(0x2006L, stored.module!!.offset)

        step("switch OFF")
        press("codeOff")
        confirmDialog()
        waitMock { hexBytes(DemoGame.SUB_ONE, 3) == "83 E8 01" }
        DemoGame.spendCoin(server)
        assertEquals(4999, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))

        step("library switches")
        tab("Cheats")
        waitText("No coin loss") // the Cheats list updated by itself
        waitText("code patch")
        press("codeSwitches-MOCK00001")
        waitTag("ghEnable-0")
        press("ghEnable-0")
        confirmDialog()
        waitMock { hexBytes(DemoGame.SUB_ONE, 3) == "90 90 90" }
        shot("44_library_on_off")

        step("session check")
        press("checkSession-MOCK00001")
        waitTag("sessionSummary")
        waitText("patched code found (ON)")
        shot("45_session_check")
    }

    /** Scrolls the Inject screen's lazy list to [t] (off-screen items are not composed) and clicks it. */
    private fun injPress(t: String) {
        compose.onNodeWithTag("injectList").performScrollToNode(hasTestTag(t))
        press(t)
    }

    @Test fun codeCaveInjectionRunsInTheGameIsSavedAndSwitchedFromCheats() {
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        // Editing and code writes are on by default (each write is still confirmed).
        assertEquals(true, c().settings.value.editingEnabled && c().settings.value.allowNonWritableRegions)

        step("disassemble and open Inject")
        compose.onNodeWithContentDescription("Code").performClick()
        waitTag("codeAddress")
        tag("codeAddress").performTextInput(Address.format(DemoGame.SUB_ONE))
        press("codeGo")
        waitText("sub eax, 1")
        step("copy the listing")
        press("codeCopyAsm")
        waitText("Copied")
        val clip = compose.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertEquals(true, clip.lines().contains("sub eax, 1"))
        press("codeCopy")
        val full = compose.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertEquals(true, full.lines().any { it.startsWith(Address.format(DemoGame.SUB_ONE)) && it.contains("eboot.bin+0x2006") && it.endsWith("sub eax, 1") })

        press("codeInject")
        waitTag("injectList")
        waitText("Source Code")
        // Lazy list: later cards are composed only once scrolled into view.
        compose.onNodeWithTag("injectList").performScrollToNode(hasTestTag("injSourceCode"))
        waitText("mov [0x500120], eax") // Source Code: the instructions the jump overwrites
        shot("53_injection_source_code")
        compose.onNodeWithTag("injectList").performScrollToNode(hasTestTag("injTarget"))
        // Target Code starts as the Source Code + jmp return: nothing replaced, one return.
        waitText("original 2/2")
        waitText("return ×1")
        compose.onNodeWithTag("injectList").performScrollToNode(hasTestTag("injBuild"))
        waitText("end of code (page padding)") // caves were searched automatically, the safest one first

        step("write the Target Code")
        compose.onNodeWithTag("injectList").performScrollToNode(hasTestTag("injTarget"))
        tag("injTarget").performTextClearance()
        // Your code before the kept original instructions, a conditional return and the final one.
        tag("injTarget").performTextInput("cmp eax, 100\njl return\nmov eax, 1000\nsub eax, 1\nmov [0x500120], eax")
        injPress("injInsertReturn")
        waitText("return ×2")
        compose.onNodeWithTag("injectList").performScrollToNode(hasTestTag("injTarget"))
        shot("54_injection_target_code")

        step("build preview")
        injPress("injBuild")
        // Building is asynchronous (assembled on the "PS4"): wait until the preview card exists, then show it.
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("injectList").performScrollToNode(hasTestTag("injPreview")) }.isSuccess }
        waitText("off (original code)")
        waitText("mov eax, 0x3E8")
        waitText("jl 0x40200F  ; return") // both returns are named in the listing
        waitText("mov [0x500120], eax") // the moved store still targets the coins
        shot("55_injection_preview")

        step("inject")
        injPress("injOn")
        confirmDialog()
        waitMock { hexBytes(DemoGame.SUB_ONE, 1) == "E9" }
        compose.waitUntil(20_000) { c().injections.value.any { it.state == com.ps4smartcheat.core.asm.InjectionState.ON } }
        val trace = DemoGame.spendCoin(server)
        assertEquals(999, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS)) // the cave code really ran
        assertEquals(true, trace.any { it >= DemoGame.TEXT_START + 0xF000 })
        shot("56_injection_on")

        step("save to cheats")
        injPress("injSave")
        waitTag("cheatName")
        tag("cheatName").performTextClearance()
        tag("cheatName").performTextInput("Coins 999")
        press("saveConfirm")
        waitTag("injSaved")
        waitText("Saved injection")
        val reloaded = com.ps4smartcheat.core.cheat.CheatRepository(java.io.File(compose.activity.filesDir, "cheats.json"))
        kotlinx.coroutines.runBlocking { reloaded.load() }
        val stored = reloaded.profiles.value.single().cheats
        assertEquals(listOf("Coins 999 · cave 1/1", "Coins 999 · jump"), stored.map { it.name })
        assertEquals(setOf("Coins 999"), stored.map { it.group }.toSet())

        step("remove")
        injPress("injOff")
        confirmDialog()
        waitMock { hexBytes(DemoGame.SUB_ONE, 3) == "83 E8 01" }
        DemoGame.spendCoin(server)
        assertEquals(998, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))

        step("switch from Cheats")
        tab("Cheats")
        waitText("Coins 999")
        press("codeSwitches-MOCK00001")
        waitTag("ghEnable-0")
        press("ghEnable-0")
        confirmDialog()
        waitMock { hexBytes(DemoGame.SUB_ONE, 1) == "E9" }
        DemoGame.spendCoin(server)
        assertEquals(999, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
        shot("57_injection_library")
        waitTag("ghDisable-0")
        press("ghDisable-0")
        confirmDialog()
        waitMock { hexBytes(DemoGame.SUB_ONE, 3) == "83 E8 01" }

        step("GoldHEN export: version format and file name")
        press("ghExport-MOCK00001")
        waitTag("ghxVersion")
        tag("ghxVersion").performTextClearance()
        tag("ghxVersion").performTextInput("1.5") // ambiguous: both forms offered, export blocked
        waitTag("ghxSuggest-01.50")
        tag("ghxExport").assertIsNotEnabled()
        tag("ghxVersion").performTextClearance()
        tag("ghxVersion").performTextInput("1.25")
        waitText("Exported as 01.25")
        waitText("MOCK00001_01.25.json")
        waitText("/user/data/GoldHEN/cheats/json/MOCK00001_01.25.json")
        shot("58_goldhen_export")
    }

    @Test fun findWhatWritesListsTheInstructionAndDetaches() {
        // Nothing is configured: the app picks its interrupt port and announces it in the attach request.
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)

        step("open code at the store")
        compose.onNodeWithContentDescription("Code").performClick()
        waitTag("codeAddress")
        tag("codeAddress").performTextInput(Address.format(DemoGame.STORE_COINS))
        press("codeGo")
        waitText("What writes?")
        compose.onAllNodesWithText("What writes?")[0].performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

        step("start watching")
        waitTag("wpStart")
        waitText(Address.format(DemoGame.COINS_ADDRESS))
        press("wpModeContinuous") // list every writer (the default is Capture once)
        press("wpStart")
        confirmDialog()
        waitText("Watching")
        val game = Thread {
            val until = System.currentTimeMillis() + 15_000
            while (server.watchpoints().getOrNull(0) == null && System.currentTimeMillis() < until) Thread.sleep(10)
            repeat(3) { DemoGame.spendCoin(server); Thread.sleep(20) }
        }.apply { isDaemon = true; start() }
        waitText("3×", timeoutMs = 30_000)
        waitText("mov [0x500120], eax")
        shot("46_what_writes")
        game.join(5_000)

        step("stop")
        press("wpStop")
        waitTag("wpReleased")
        waitText("debugger detached")
        waitMock { !server.debugAttached && !server.gamePaused }

        step("activity check")
        openSettings()
        waitTag("openDiagnostics")
        press("openDiagnostics")
        press("activityCheck")
        waitTag("activityResult")
        waitText("pid 77")
        waitText("watchpoints ready") // capability level 1.2 shown in Diagnostics
        shot("47_activity_check")
    }

    @Test fun captureOnceSavesTheFirstWriterForLater() {
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        compose.onNodeWithContentDescription("Code").performClick()
        waitTag("codeAddress")
        tag("codeAddress").performTextInput(Address.format(DemoGame.STORE_COINS))
        press("codeGo")
        waitText("What writes?")
        compose.onAllNodesWithText("What writes?")[0].performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

        step("capture once (default mode)")
        waitTag("wpStart")
        press("wpStart")
        confirmDialog()
        val game = Thread {
            val until = System.currentTimeMillis() + 15_000
            while (server.watchpoints().getOrNull(0) == null && System.currentTimeMillis() < until) Thread.sleep(10)
            repeat(2) { DemoGame.spendCoin(server); Thread.sleep(20) }
        }.apply { isDaemon = true; start() }
        waitTag("wpReleased", timeoutMs = 30_000)
        waitText("1×")
        waitText("first hit captured")
        game.join(5_000)
        assertEquals(false, game.isAlive) // the second run of the routine was not paused any more
        waitMock { !server.debugAttached && !server.gamePaused }
        assertEquals(4998, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
        shot("49_capture_once")

        step("open the saved result without the debugger")
        val savedTitle = "Writes to " + Address.format(DemoGame.COINS_ADDRESS)
        compose.onNodeWithTag("watchList").performScrollToNode(hasText(savedTitle))
        waitText(savedTitle)
        compose.onAllNodesWithText(savedTitle)[0].performClick()
        compose.onNodeWithTag("watchList").performScrollToNode(hasText("eboot.bin+0x2009", substring = true))
        waitText("eboot.bin+0x2009")
        shot("50_saved_results")
        compose.onNodeWithTag("watchList").performScrollToNode(hasText("Open"))
        compose.onAllNodesWithText("Open")[0].performSemanticsAction(SemanticsActions.OnClick)
        waitTag("codeAddress")
        waitText("mov [0x500120], eax")

        step("register values in the disassembler")
        waitTag("regCard")
        waitTag("regUse-eax")
        waitText("0x00001387 (4999)") // eax when the store hit the watchpoint (coins 4999), hex and decimal
        waitTag("regOperand")
        waitText("now in memory: 4998") // the routine ran once more after the capture
        shot("51_registers_in_disassembler")
        val hitRow = "regRow-" + java.lang.Long.toHexString(DemoGame.STORE_COINS)
        // The row is clickable (its children's tags are merged away): scroll to the row itself, then check the
        // inline register line in the unmerged tree.
        compose.onNodeWithTag("codeList").performScrollToNode(hasTestTag("asm_" + java.lang.Long.toHexString(DemoGame.STORE_COINS)))
        waitTag(hitRow)
        waitText("◆ hit")

        step("call stack")
        compose.onNodeWithTag("codeList").performScrollToNode(hasTestTag("callStack"))
        waitText("call 0x402000") // UPDATE called the spend routine (direct call, verified)
        waitText("call verified")
        waitText("call rax") // GAME_LOOP called UPDATE indirectly; found through the frame pointer chain
        waitText("frame pointer chain")
        shot("59_call_stack")
        press("callStack-copy")
        val clip = compose.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertEquals(true, clip.lines().any { it.startsWith("#1  " + Address.format(DemoGame.UPDATE + 4)) && it.contains("call 0x402000") })
        press("callStack-1") // open the caller
        waitText("Patch at " + Address.format(DemoGame.UPDATE + 4))
    }

    /** The mock game runs its loop once a breakpoint is armed. */
    private fun runGameWhenArmed(times: Int = 1) = Thread {
        val until = System.currentTimeMillis() + 20_000
        while (server.watchpoints().none { it != null } && System.currentTimeMillis() < until) Thread.sleep(10)
        repeat(times) { DemoGame.spendCoin(server); Thread.sleep(20) }
    }.apply { isDaemon = true; start() }

    private fun dbgPress(t: String) {
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("debugList").performScrollToNode(hasTestTag(t)) }.isSuccess }
        press(t)
    }

    @Test fun breakpointRegisterTraceAndManualStepping() {
        server.stopTimeoutMs = 60_000 // the UI test may take a while between a stop and the next command
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        compose.onNodeWithContentDescription("Code").performClick()
        waitTag("codeAddress")
        tag("codeAddress").performTextInput(Address.format(DemoGame.UPDATE))
        press("codeGo")
        waitText("push rbp")
        press("codeDebug")
        waitTag("dbgAddress")

        step("register trace")
        dbgPress("dbgMode-TRACE")
        dbgPress("dbgStart")
        confirmDialog()
        var game = runGameWhenArmed()
        waitMock(30_000) { !server.debugAttached && server.steps.get() > 0 }
        game.join(10_000)
        assertEquals(false, game.isAlive)
        assertEquals(4999, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS)) // the routine ran while being stepped over
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("debugList").performScrollToNode(hasTestTag("dbgReleased")) }.isSuccess }
        waitText("6 instruction(s), the function returned")
        compose.onNodeWithTag("debugList").performScrollToNode(hasTestTag("dbgHit"))
        waitText("call rax") // the caller, from the call stack at the hit
        compose.onNodeWithTag("debugList").performScrollToNode(hasTestTag("dbgStepRow-3"))
        waitText("after call")
        waitText("0x0000000000001387 (4999)") // rax changed by the stepped-over routine, hex and decimal
        shot("60_register_trace")
        dbgPress("dbgCopy")
        val clip = compose.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertEquals(6, clip.lines().size)
        assertEquals(true, clip.lines().first().startsWith("#0  " + Address.format(DemoGame.UPDATE)) && clip.lines().first().endsWith("push rbp"))

        step("break and step")
        dbgPress("dbgMode-MANUAL")
        dbgPress("dbgStart")
        confirmDialog()
        val stepsBefore = server.steps.get()
        game = runGameWhenArmed()
        waitMock(30_000) { server.gamePaused }
        dbgPress("dbgStep")
        waitMock { server.steps.get() == stepsBefore + 1 && server.gamePaused }
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("debugList").performScrollToNode(hasTestTag("dbgStepRow-2")) }.isSuccess }
        shot("61_break_and_step")
        dbgPress("dbgResume")
        waitMock(30_000) { !server.debugAttached && !server.gamePaused }
        game.join(10_000)
        assertEquals(false, game.isAlive)
        assertEquals(4998, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
    }

    @Test fun payloadBelowCapability12DisablesWatchpoints() {
        server.faults.capability("1.1") // a released ps4debug-NG without the configurable interrupt port
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        compose.onNodeWithContentDescription("Code").performClick()
        waitTag("codeAddress")
        tag("codeAddress").performTextInput(Address.format(DemoGame.STORE_COINS))
        press("codeGo")
        waitText("What writes?")
        compose.onAllNodesWithText("What writes?")[0].performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitTag("wpStart")
        waitText("capability level 1.1")
        compose.onNodeWithTag("wpStart", useUnmergedTree = true).assertIsNotEnabled()
        assertEquals(null, server.lastAttachBodySize) // nothing was sent to the PS4
        shot("48_watchpoints_need_level_12")
    }

    @Test fun toolsOpenInTabsThatKeepTheirWork() {
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        val tabs = c().tabs
        val start = tabs.tabs.value.size

        step("disassembler tab")
        compose.onNodeWithContentDescription("Code").performClick()
        waitTag("codeAddress")
        tag("codeAddress").performTextInput(Address.format(DemoGame.STORE_COINS))
        press("codeGo")
        waitText("What writes?")
        assertEquals(1, chips(Tool.CODE))

        step("second disassembler from the new-tab page")
        tag("newTab").performClick()
        waitTag("toolLauncher")
        shot("50_new_tab_page")
        tag("toolLauncher").performScrollToNode(hasTestTag("tool-CODE"))
        tag("tool-CODE").performClick()
        waitTag("codeAddress")
        assertEquals(2, chips(Tool.CODE))
        assertEquals(start + 2, tabs.tabs.value.size)
        assertEquals(true, compose.onAllNodesWithText("What writes?").fetchSemanticsNodes().isEmpty()) // its own, empty state

        step("switching tabs keeps each tool's work")
        tab("Scanner")
        waitTag("scanValue")
        tag("scanValue").performTextInput("5000")
        tag("firstScan").performClick()
        waitText("Scan 1: ")
        tag("tabStrip").performScrollToNode(hasTestTag("tabChip-CODE"))
        compose.onAllNodesWithTag("tabChip-CODE")[0].performClick()
        waitText("What writes?") // the first disassembler still shows its code
        shot("51_tabs_keep_state")
        tab("Scanner")
        waitText("Scan 1: ") // the scan is still there

        step("back returns to the tab used before")
        backPress()
        waitText("What writes?")
        assertEquals(Tool.CODE, tabs.active.tool)

        step("overview: reorder and close")
        tag("tabCount").performClick()
        waitTag("tabOverview")
        shot("52_tab_overview")
        val before = tabs.tabs.value.map { it.id }
        val firstCode = tabs.tabs.value.indexOfFirst { it.tool == Tool.CODE }
        compose.onAllNodesWithContentDescription("Move Code left")[0].performClick()
        compose.waitForIdle()
        assertEquals(before[firstCode], tabs.tabs.value[firstCode - 1].id)
        compose.onAllNodesWithContentDescription("Close Code")[1].performClick()
        compose.waitForIdle()
        assertEquals(1, tabs.tabs.value.count { it.tool == Tool.CODE })
        compose.onAllNodesWithTag("overviewCard-SCANNER")[0].performClick()
        waitText("Scan 1: ")

        step("tab strip: close a tab")
        tag("tabStrip").performScrollToNode(hasTestTag("tabClose-CODE"))
        compose.onAllNodesWithTag("tabClose-CODE", useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        assertEquals(0, tabs.tabs.value.count { it.tool == Tool.CODE })
        assertEquals(start, tabs.tabs.value.size)
    }

    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    @Test fun tabletLayoutShowsTabsAndWideContent() {
        connect()
        waitText("eboot.bin (77)", timeoutMs = 20_000)
        tab("Scanner")
        waitTag("scanValue")
        tag("scanValue").performTextInput("5000")
        tag("firstScan").performClick()
        waitText("Scan 1: ")
        shot("40_tablet_scanner")
        tab("Addresses")
        waitTag("addressList")
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
