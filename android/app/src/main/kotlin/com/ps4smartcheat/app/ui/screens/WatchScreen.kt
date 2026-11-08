package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.ps4smartcheat.app.ui.components.ConfirmDialog
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.Selector
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.debug.AccessMonitor
import com.ps4smartcheat.core.debug.AccessRecord
import com.ps4smartcheat.core.debug.DebugSession
import com.ps4smartcheat.core.debug.InterruptListener
import com.ps4smartcheat.core.debug.MonitorOptions
import com.ps4smartcheat.core.debug.WatchType
import com.ps4smartcheat.core.diag.ErrorAdvisor
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.protocol.NetworkPolicy
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

/**
 * "Find out what writes / accesses this address" with a ps4debug-NG hardware watchpoint. Nothing is written to game
 * memory; the debugger pauses the game briefly at every hit and the app resumes it immediately. The session ends
 * (debugger detached) when stopped, at the hit/time limit, when leaving the screen or when the connection drops.
 */
class WatchViewModel(private val c: AppContainer) : ViewModel() {
    val selected = c.selectedProcess
    val connection = c.backend.state
    val settings = c.settings.settings

    private val _address = MutableStateFlow("")
    val address = _address.asStateFlow()
    private val _size = MutableStateFlow(4)
    val size = _size.asStateFlow()
    private val _type = MutableStateFlow(WatchType.WRITES)
    val type = _type.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _records = MutableStateFlow<List<AccessRecord>>(emptyList())
    val records = _records.asStateFlow()
    private val _hits = MutableStateFlow(0)
    val hits = _hits.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var job: Job? = null
    private var initialized = false

    fun init(address: Long?, size: Int?, writes: Boolean?) {
        if (initialized) return
        initialized = true
        address?.let { _address.value = Address.format(it) }
        size?.takeIf { it in SIZES }?.let { _size.value = it }
        writes?.let { _type.value = if (it) WatchType.WRITES else WatchType.ACCESSES }
    }

    fun setAddress(v: String) { _address.value = v.trim(); _error.value = null }
    fun setSize(v: Int) { _size.value = v }
    fun setType(v: WatchType) { _type.value = v }

    /** Address and size the watchpoint will use (the size shrinks to the alignment of the address). */
    fun target(): Pair<Long, Int>? {
        val a = Address.parse(_address.value) ?: return null
        var s = _size.value
        while (s > 1 && a % s != 0L) s /= 2
        return a to s
    }

    fun start() {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select the game process first"; return }
        val (addr, size) = target() ?: run { _error.value = "Enter a hexadecimal address"; return }
        if (_running.value) return
        val st = c.settings.value
        _records.value = emptyList(); _hits.value = 0; _error.value = null
        _running.value = true
        _status.value = "Attaching…"
        job = viewModelScope.launch {
            try {
                val relay = st.interruptRelay.trim().ifEmpty { null }?.let { h ->
                    val ip = withContext(Dispatchers.IO) { InetAddress.getByName(h) }
                    require(ip is Inet4Address) { "The relay needs an IPv4 address" }
                    require(st.allowNonLocalHosts || NetworkPolicy.isLocal(ip)) { "The relay must be on the local network" }
                    ip
                }
                val listener = withContext(Dispatchers.IO) { InterruptListener.open(st.interruptPort) }
                val session = DebugSession.attach(c.transport, sel.pid, listener, relay)
                c.log.event(com.ps4smartcheat.core.logging.EventLevel.INFO, EventCategory.PROCESS, "debugger attached", "pid" to sel.pid, "watch" to Address.format(addr), "type" to _type.value.name)
                _status.value = "Watching ${Address.format(addr)} ($size bytes, ${_type.value.label}) — play the game until the value changes"
                val result = AccessMonitor(c.backend).run(session, addr, size, MonitorOptions(_type.value, maxHits = MAX_HITS, maxMillis = MAX_MILLIS)) { recs, n ->
                    _records.value = recs; _hits.value = n
                }
                _records.value = result.records
                _status.value = "Finished (${result.stopReason}). ${result.hits} hit(s); debugger detached."
            } catch (e: CancellationException) {
                _status.value = "Stopped; debugger detached."
                throw e
            } catch (e: BackendException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
                _status.value = null
            } catch (e: IllegalArgumentException) {
                _error.value = e.message
                _status.value = null
            } catch (e: java.io.IOException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
                _status.value = null
            } finally {
                _running.value = false
                c.log.info("debugger session ended", EventCategory.PROCESS)
            }
        }
    }

    fun stop() { job?.cancel() }

    companion object {
        val SIZES = listOf(1, 2, 4, 8)
        const val MAX_HITS = 1000
        const val MAX_MILLIS = 120_000L
    }
}

@Composable
fun WatchScreen(initialAddress: Long?, initialSize: Int?, writes: Boolean?, onOpenCode: (Long) -> Unit) {
    val vm = appViewModel { WatchViewModel(it) }
    LaunchedEffect(Unit) { vm.init(initialAddress, initialSize, writes) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val size by vm.size.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val info = (connection as? ConnectionState.Connected)?.info
    val ng = info?.capabilities?.isNg == true
    var confirm by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected == null) WarningBanner("No process selected.")
                if (info != null && !ng) WarningBanner("Watchpoints need ps4debug-NG.")
                Text(
                    "Uses a hardware watchpoint of the PS4 debugger. Every hit pauses the game for a moment; the app resumes it " +
                        "immediately. Game memory is not changed. Offline games only.",
                    style = MaterialTheme.typography.bodySmall, color = dim,
                )
                OutlinedTextField(
                    value = address, onValueChange = vm::setAddress, label = { Text("Address (hex)") }, singleLine = true, enabled = !running,
                    modifier = Modifier.fillMaxWidth().testTag("wpAddress"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Selector("Find what…", WatchType.entries, type, { if (it == WatchType.WRITES) "writes" else "accesses (read or write)" }, vm::setType, Modifier.weight(1f), enabled = !running)
                    Selector("Size", WatchViewModel.SIZES, size, { "$it bytes" }, vm::setSize, Modifier.weight(1f), enabled = !running)
                }
                vm.target()?.let { (a, s) -> if (s != size) Text("${Address.format(a)} is only $s-byte aligned: watching $s byte(s).", style = MaterialTheme.typography.bodySmall, color = dim) }
                Text(
                    "Interrupt connection: port ${settings.interruptPort}" + (settings.interruptRelay.ifBlank { null }?.let { " via relay $it" } ?: " on this device") +
                        if (settings.interruptPort != 755 && settings.interruptRelay.isBlank()) " (the PS4 connects to 755 — set a relay in Settings)" else "",
                    style = MaterialTheme.typography.bodySmall, color = dim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirm = true }, enabled = !running && selected != null && ng && selected?.exited == false, modifier = Modifier.testTag("wpStart")) { Text("Start") }
                    OutlinedButton(onClick = vm::stop, enabled = running, modifier = Modifier.testTag("wpStop")) { Text("Stop") }
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("wpStatus")) }
                ErrorText(error)
                if (records.isNotEmpty() || running) Text("$hits hit(s) · ${records.size} instruction(s)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("wpHits"))
            }
        }
        items(records, key = { it.instructionAddress }) { r ->
            SectionCard(null) {
                Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == r.instructionAddress) null else r.instructionAddress }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.count}×  ${Address.format(r.instructionAddress)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("wpRecord"))
                    Text(r.instruction?.text ?: "(instruction before ${Address.format(r.trapRip)} not decoded)", style = mono)
                    Text("thread ${r.threadName}" + (r.effectiveAddress?.let { " · accessed ${Address.format(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = dim)
                    if (expanded == r.instructionAddress) {
                        r.registers.general.chunked(2).forEach { pair ->
                            Text(pair.joinToString("   ") { (n, v) -> n.padEnd(6) + "%016X".format(v) }, style = mono)
                        }
                    }
                    OutlinedButton(onClick = { onOpenCode(r.instructionAddress) }) { Text("Open in disassembler") }
                }
            }
        }
    }

    if (confirm) ConfirmDialog(
        title = "Attach the debugger?",
        text = "ps4debug-NG attaches to ${selected?.name} (pid ${selected?.pid}) and sets a hardware watchpoint on ${vm.target()?.first?.let { Address.format(it) } ?: "?"}. " +
            "The game pauses briefly at each hit. Monitoring stops after ${WatchViewModel.MAX_HITS} hits or ${WatchViewModel.MAX_MILLIS / 1000} s, and the debugger is detached.",
        confirmLabel = "Attach",
        onConfirm = { confirm = false; vm.start() },
        onDismiss = { confirm = false },
    )
}
