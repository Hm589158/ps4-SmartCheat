package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.KeyValue
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.protocol.NetworkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectionViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<ConnectionState> = c.backend.state
    private val _host = MutableStateFlow(c.settings.value.host)
    val host = _host.asStateFlow()
    private val _port = MutableStateFlow(c.settings.value.port.toString())
    val port = _port.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun setHost(v: String) { _host.value = v.trim(); _error.value = null }
    fun setPort(v: String) { _port.value = v.filter { it.isDigit() }.take(5); _error.value = null }

    fun connect() {
        val host = _host.value
        val port = _port.value.toIntOrNull() ?: -1
        val problem = NetworkPolicy.validateHostSyntax(host) ?: NetworkPolicy.validatePort(port)
        if (problem != null) { _error.value = problem; return }
        c.settings.update { it.copy(host = host, port = port) }
        _error.value = null
        viewModelScope.launch {
            try {
                c.backend.connect(c.settings.value.connectOptions())
            } catch (e: BackendException) {
                _error.value = e.message
                c.log.error("Connect to $host:$port failed: ${e.message}", notifyUser = false)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { c.userDisconnect() }
    }
}

@Composable
fun ConnectionScreen(onConnected: () -> Unit) {
    val vm = appViewModel { ConnectionViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val host by vm.host.collectAsStateWithLifecycle()
    val port by vm.port.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val connected = state is ConnectionState.Connected
    val connecting = state is ConnectionState.Connecting || state is ConnectionState.Reconnecting

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("PS4 address") {
            OutlinedTextField(
                value = host, onValueChange = vm::setHost, label = { Text("IP address (e.g. 192.168.1.50)") },
                singleLine = true, enabled = !connected && !connecting, modifier = Modifier.fillMaxWidth().testTag("host"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = port, onValueChange = vm::setPort, label = { Text("Port (ps4debug-NG default 744)") },
                singleLine = true, enabled = !connected && !connecting, modifier = Modifier.fillMaxWidth().testTag("port"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            ErrorText(error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (connected) {
                    OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                    Button(onClick = onConnected) { Text("Processes") }
                } else {
                    Button(onClick = vm::connect, enabled = !connecting, modifier = Modifier.testTag("connect")) { Text(if (state is ConnectionState.Failed) "Reconnect" else "Connect") }
                    if (connecting) CircularProgressIndicator(Modifier.padding(start = 8.dp))
                }
            }
        }

        SectionCard("Status") {
            val (pill, tone) = when (val s = state) {
                ConnectionState.Disconnected -> "Not connected" to com.ps4smartcheat.app.ui.components.Tone.NEUTRAL
                is ConnectionState.Connecting -> "Connecting…" to com.ps4smartcheat.app.ui.components.Tone.WARN
                is ConnectionState.Reconnecting -> "Reconnecting ${s.attempt}/${s.maxAttempts}" to com.ps4smartcheat.app.ui.components.Tone.WARN
                is ConnectionState.Failed -> "Connection lost" to com.ps4smartcheat.app.ui.components.Tone.BAD
                is ConnectionState.Connected -> "Connected" to com.ps4smartcheat.app.ui.components.Tone.GOOD
            }
            com.ps4smartcheat.app.ui.components.StatusPill(pill, tone, Modifier.testTag("connectionPill"))
            when (val s = state) {
                ConnectionState.Disconnected -> Text("Not connected")
                is ConnectionState.Connecting -> Text("Connecting to ${s.host}:${s.port}…")
                is ConnectionState.Reconnecting -> Text(
                    "Connection dropped (${s.reason}). Reconnecting ${s.attempt}/${s.maxAttempts}… Writes are never repeated automatically.",
                    color = MaterialTheme.colorScheme.tertiary,
                )
                is ConnectionState.Failed -> Text("Connection failed: ${s.message}", color = MaterialTheme.colorScheme.error)
                is ConnectionState.Connected -> {
                    KeyValue("Console", "${s.info.host}:${s.info.port}", mono = true)
                    val caps = s.info.capabilities
                    KeyValue("Backend", if (caps.isNg) "ps4debug-NG (primary)" else "classic ps4debug (compatibility mode)")
                    KeyValue(
                        "Scan auth",
                        when {
                            !caps.isNg -> "n/a — server-side scanning needs ps4debug-NG"
                            caps.scanAuthorized -> "granted (CMD_PROC_AUTH)"
                            else -> "failed"
                        },
                    )
                    if (caps.isNg) KeyValue(
                        "Capability",
                        (caps.capabilityLevel ?: "none") + if (caps.attachPort) " · watchpoints ready" else " · watchpoints need 1.2",
                    )
                    KeyValue("Debugger ver.", s.info.debuggerVersion.ifBlank { "unknown" })
                    KeyValue("Branding", caps.brand ?: "not reported")
                    KeyValue(
                        "Firmware",
                        s.info.firmwareText?.let { fw -> fw + (com.ps4smartcheat.core.protocol.Ps4Firmware.note(s.info.firmware)?.let { " · $it" } ?: "") }
                            ?: "unknown (not reported by payload)",
                    )
                    KeyValue(
                        "Scan backends",
                        buildList {
                            if (caps.turboResident) add("Turbo (PS4)")
                            if (caps.serverScan) add("Server-side (PS4)")
                            add("Android")
                        }.joinToString(" → "),
                    )
                    if (!caps.isNg) WarningBanner(
                        "Classic ps4debug detected. Use ps4debug-NG for scanning and editing: it is the debugger this app is " +
                            "built and tested for (github.com/Pharaoh2k/ps4debug-NG). Classic ps4debug runs in an untested compatibility mode.",
                    )
                    Text(
                        "The connection stays open while you switch to the game or another app (notification \"Connected to the PS4\"). " +
                            "If it is lost anyway, the app reconnects when you come back.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (caps.isNg && !caps.scanAuthorized) Text("ps4debug-NG detected but the scan handshake failed; scans run on Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }

        WarningBanner(
            "ps4debug is an unauthenticated debugging service: anyone on your network can read and write " +
                "console memory while it runs. Use it only on a trusted home network and never expose port 744 " +
                "to the internet. Connections to non-local addresses are blocked by default.",
        )
        SectionCard("Before connecting") {
            Text(
                "1. Jailbroken PS4 with GoldHEN.\n2. Send ps4debug-NG (ps4debug-ng_…bin from github.com/Pharaoh2k/ps4debug-NG/releases) " +
                    "to the payload loader — required for scanning and editing.\n" +
                    "3. Phone and PS4 on the same Wi-Fi/LAN.\n4. Start the offline game, then connect to the PS4's IP on port 744.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Any firmware ps4debug-NG supports (${com.ps4smartcheat.core.protocol.Ps4Firmware.SUPPORTED_BY_PS4DEBUG_NG.size} versions, " +
                    "${com.ps4smartcheat.core.protocol.Ps4Firmware.range}). The core flow (connect, Turbo Scan, write) is reported working on " +
                    "a PS4 (FW 11.00); newer tools are still being verified on hardware. Report problems on GitHub.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
