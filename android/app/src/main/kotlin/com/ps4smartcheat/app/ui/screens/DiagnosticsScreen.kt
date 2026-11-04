package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.KeyValue
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.diag.LinkDiagnostics
import com.ps4smartcheat.core.diag.PingStats
import com.ps4smartcheat.core.logging.EventLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiagnosticsViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.backend.state
    val lines = c.log.lines
    private val _ping = MutableStateFlow<PingStats?>(null)
    val ping = _ping.asStateFlow()
    private val _pinging = MutableStateFlow(false)
    val pinging = _pinging.asStateFlow()

    fun traffic() = c.backend.traffic()
    fun idleMs() = c.backend.idleMillis()
    fun reconnects() = c.reconnectCount
    fun link() = c.scanner.linkEstimate

    /** Five CMD_VERSION round trips: read-only, no game memory is touched. */
    fun runPing() {
        if (_pinging.value || c.backend.state.value !is ConnectionState.Connected) return
        _pinging.value = true
        viewModelScope.launch {
            try { _ping.value = LinkDiagnostics.ping(c.backend, count = 5) } finally { _pinging.value = false }
        }
    }
}

private fun bytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KiB".format(b / 1024.0)
    else -> "%.1f MiB".format(b / 1048576.0)
}

@Composable
fun DiagnosticsScreen() {
    val vm = appViewModel { DiagnosticsViewModel(it) }
    val connection by vm.connection.collectAsStateWithLifecycle()
    val ping by vm.ping.collectAsStateWithLifecycle()
    val pinging by vm.pinging.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    // Re-read the counters once a second while the screen is shown.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Connection") {
            when (val st = connection) {
                is ConnectionState.Connected -> {
                    val caps = st.info.capabilities
                    KeyValue("Console", "${st.info.host}:${st.info.port}", mono = true)
                    KeyValue("Debugger", caps.brand ?: "ps4debug ${st.info.debuggerVersion}")
                    KeyValue("Firmware", st.info.firmwareText ?: "unknown")
                    KeyValue("Scan auth", if (caps.isNg) (if (caps.scanAuthorized) "granted" else "not granted") else "n/a (classic)")
                    KeyValue("Turbo engines", caps.turboEngines?.let { "0x" + Integer.toHexString(it).uppercase() } ?: "none")
                }
                is ConnectionState.Reconnecting -> KeyValue("State", "Reconnecting ${st.attempt}/${st.maxAttempts}: ${st.reason}")
                is ConnectionState.Failed -> KeyValue("State", "Connection lost: ${st.message}")
                is ConnectionState.Connecting -> KeyValue("State", "Connecting…")
                ConnectionState.Disconnected -> KeyValue("State", "Not connected")
            }
            tick.let { _ ->
                val t = vm.traffic()
                KeyValue("Traffic", "in ${bytes(t.bytesIn)} · out ${bytes(t.bytesOut)}", mono = true)
                KeyValue("Idle", "${vm.idleMs() / 1000} s")
                KeyValue("Reconnects", vm.reconnects().toString())
                vm.link()?.let { KeyValue("Measured link", "RTT %.1f ms · %.1f MiB/s".format(it.rttMs, it.bytesPerSecond / 1048576.0)) }
            }
        }
        SectionCard("Latency test") {
            Text("Sends 5 version requests (no game memory is read).", style = MaterialTheme.typography.bodySmall)
            Button(onClick = vm::runPing, enabled = connection is ConnectionState.Connected && !pinging, modifier = Modifier.testTag("pingTest")) { Text("Run") }
            if (pinging) LinearProgressIndicator()
            ping?.let { p ->
                if (p.samples.isEmpty()) Text("No reply (${p.failures} failed).", color = MaterialTheme.colorScheme.error)
                else Text(
                    "min %.1f · avg %.1f · max %.1f ms · jitter %.1f ms".format(p.minMs, p.avgMs, p.maxMs, p.jitterMs ?: 0.0) +
                        if (p.failures > 0) " · ${p.failures} failed" else "",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pingResult"),
                )
                p.avgMs?.let { if (it > 50) WarningBanner("High latency: use 5 GHz Wi-Fi or Ethernet on the PS4; Turbo Scan keeps scans on the console and is least affected.") }
            }
        }
        SectionCard("Recent warnings and errors") {
            val recent = lines.filter { it.event.level >= EventLevel.WARN }.takeLast(15)
            if (recent.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall)
            recent.forEach { Text(it.format(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            Text("Full log: Settings → Export (JSON Lines).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
