package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.WarningBanner

private data class HelpSection(val title: String, val steps: List<String>)

private val sections = listOf(
    HelpSection(
        "1. Connect",
        listOf(
            "On the PS4 (jailbroken, GoldHEN): send ps4debug-NG to the payload loader (port 9020 or 9021, depending on the jailbreak). Download: github.com/Pharaoh2k/ps4debug-NG/releases. ps4debug-NG is required for scanning and editing.",
            "Phone and PS4 on the same local network. Enter the PS4's IP, port 744, tap Connect.",
            "ps4debug-NG unlocks Turbo Scan and server-side scans automatically (authentication is handled by the app).",
        ),
    ),
    HelpSection(
        "2. Find a value",
        listOf(
            "Start the game; it is selected automatically (or pick it on Processes).",
            "Scanner → type and value as shown in the game (e.g. ammo 30) → First scan.",
            "Change the value in the game (fire once), enter the new value → Next scan. Repeat until a few results remain.",
            "Unknown value? First scan \"Unknown initial value\", then Increased/Decreased/Changed. Health bars: try Float with \"Value between\".",
        ),
    ),
    HelpSection(
        "3. Edit safely",
        listOf(
            "The app starts read-only. Enable memory editing in Settings when you want to write.",
            "Tap a result → Editor → new value → Write → confirm. Every write is verified by reading it back.",
            "Restore puts the original back; History → Undo/Redo steps through your writes (each confirmed).",
            "Freeze rewrites a value while the phone stays connected; it stops on disconnect or when the game exits.",
        ),
    ),
    HelpSection(
        "4. Keep what you found",
        listOf(
            "+ on a result (or \"+ List\" in the editor) saves it to the game's Address list with live values.",
            "Addresses inside the game executable survive restarts. Heap addresses change every run: open the entry → \"Find pointers…\", restart the game, find the value again and filter the chains.",
            "\"Save as cheat\" stores an entry in the cheat library; profiles with title ID and game version export to GoldHEN JSON.",
            "\"Open GoldHEN file…\" checks an existing cheat file against the running game before enabling any mod.",
        ),
    ),
    HelpSection(
        "5. Code: Assembly ON/OFF cheats",
        listOf(
            "Open Code (wrench icon), or \"Disassemble\" in the memory viewer. Instructions are decoded on the phone; W marks writes, R reads.",
            "Tap the instruction to change, type new instructions (e.g. nop, or mov eax, 999) → Preview. The PS4 assembles them at that address (ps4debug-NG). Whole instructions are replaced and padded with NOPs; the preview shows ON and OFF bytes and warnings.",
            "Enable editing and \"Allow writes to non-writable regions\" in Settings, then ON (1) writes the new code and OFF (0) restores the original — each confirmed and verified.",
            "Save… stores the patch (with an optional group) in the cheat library: Cheats → ON/OFF switches, and GoldHEN export, so GoldHEN can switch it from its menu without the phone.",
            "Longer code: tap the instruction → Inject code…. A jump leads to a code cave (unused padding in the game, found automatically, or memory allocated near it for this session) with your code, the overwritten instructions (moved and re-encoded) and a jump back. Build preview, then Inject (ON) / Remove (OFF); the cave is written before the jump and the jump removed first. Caves in the game can be saved to Cheats and exported to GoldHEN as one mod.",
        ),
    ),
    HelpSection(
        "6. What writes / accesses this address",
        listOf(
            "Address list → open an entry → \"What writes?\" (or from Code). The PS4 debugger sets a hardware watchpoint; play until the value changes. Each instruction is listed with its hit count and registers; open it in Code to patch it.",
            "Needs ps4debug-NG with capability level 1.2 (configurable interrupt port, Pharaoh2k/ps4debug-NG pull request #1). The app checks the level when it connects (Diagnostics shows it) and sets everything up itself: no root and no extra computer.",
            "Capture once (default) pauses the game a single time and records the first instruction with its registers. Monitor lists every instruction; it stops by itself after 1000 hits, 2 minutes or when hits come too fast. Each pause is resumed with a verified CONTINUE and the debugger is always released; finished sessions are saved under Saved results.",
        ),
    ),
    HelpSection(
        "7. After a restart or update",
        listOf(
            "Cheats → \"Check after restart\" checks the address list and the profile: module offsets, pointer chains, writable values, and code patches (original or patched bytes). Read-only.",
            "If a code patch's bytes changed (another game version), its saved signature is searched in the executable and the new offset is shown; nothing is changed automatically.",
            "Pointer scan → Save as candidates, then after each restart Validate: chains that keep leading to the value rank first.",
        ),
    ),
    HelpSection(
        "8. Troubleshooting",
        listOf(
            "Connection refused: the payload is not running or the IP/port is wrong.",
            "Scans slow: use Turbo (ps4debug-NG) and 5 GHz Wi-Fi or Ethernet; Settings → Max region size skips huge regions.",
            "Settings → Connection diagnostics: latency test, traffic, reconnects, game activity check, recent errors. Export the diagnostic report or the log to report problems.",
            "Game frozen after a debugger session? Diagnostics → Check game activity → Resume process.",
        ),
    ),
)

@Composable
fun HelpScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("help"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WarningBanner("Beta. Offline, single-player use on your own console only. No online play, anti-cheat bypass or save editing. ps4debug has no authentication: use it only on a trusted local network.")
        sections.forEach { s ->
            SectionCard(s.title) {
                s.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
