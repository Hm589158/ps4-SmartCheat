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
        "5. Troubleshooting",
        listOf(
            "Connection refused: the payload is not running or the IP/port is wrong.",
            "Scans slow: use Turbo (ps4debug-NG) and 5 GHz Wi-Fi or Ethernet; Settings → Max region size skips huge regions.",
            "Settings → Connection diagnostics: latency test, traffic, reconnects, recent errors. Export the log to report problems.",
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
