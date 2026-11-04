package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.LabeledSwitch
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.scan.describe
import androidx.compose.foundation.background
import com.ps4smartcheat.core.tools.HexDump
import com.ps4smartcheat.core.tools.HexRow
import com.ps4smartcheat.core.tools.MemoryViewer
import com.ps4smartcheat.core.tools.MemoryWindow
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Read-only memory viewer with change highlighting (memory diff between consecutive reads). */
class HexViewerViewModel(private val c: AppContainer) : ViewModel() {
    val selected = c.selectedProcess
    val connection = c.backend.state
    private val viewer = MemoryViewer(c.backend)

    private val _address = MutableStateFlow("")
    val address = _address.asStateFlow()
    private val _rows = MutableStateFlow<List<HexRow>>(emptyList())
    val rows = _rows.asStateFlow()
    private val _changed = MutableStateFlow(0)
    val changed = _changed.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _live = MutableStateFlow(false)
    val live = _live.asStateFlow()
    private var window: MemoryWindow? = null
    private var start: Long? = null
    private val _target = MutableStateFlow<Long?>(null)
    /** The address the user asked for (highlighted). */
    val target = _target.asStateFlow()
    private val _targetInfo = MutableStateFlow<String?>(null)
    /** Typed interpretation of the bytes at [target]. */
    val targetInfo = _targetInfo.asStateFlow()
    private var initialized = false

    val intervalMs: Long get() = c.settings.value.monitorIntervalMs.coerceIn(250, 10_000).toLong()

    fun init(address: Long?) {
        if (initialized) return
        initialized = true
        if (address != null) {
            _address.value = Address.format(address)
            go()
        }
    }

    fun setAddress(v: String) { _address.value = v.trim(); _error.value = null }
    fun setLive(v: Boolean) { _live.value = v }

    fun go() {
        val a = Address.parse(_address.value) ?: run { _error.value = "Enter a hexadecimal address"; return }
        start = HexDump.alignDown(a - WINDOW / 2, ROW)
        _target.value = a
        window = null
        refresh()
    }

    fun page(delta: Int) {
        val s = start ?: return
        start = s + delta.toLong() * WINDOW
        _address.value = Address.format(start!! + WINDOW / 2)
        _target.value = start!! + WINDOW / 2
        window = null
        refresh()
    }

    fun refresh() {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select a process first"; return }
        val s = start ?: return
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val w = viewer.read(sel.pid, s, WINDOW, sel.regions)
                val rows = HexDump.rows(w, window, ROW)
                _changed.value = HexDump.changedCount(rows)
                _rows.value = rows
                window = w
                _targetInfo.value = _target.value?.let { describeValues(w, it) }
                _error.value = if (w.mapped.none { it }) "No readable memory in this window" else null
            } catch (e: BackendException) {
                _error.value = e.message
                _live.value = false
            } finally {
                _loading.value = false
            }
        }
    }

    /** "Int32 4200 · Int16 4200 · Float 5.9E-42" for the bytes at [address], if mapped. */
    private fun describeValues(w: MemoryWindow, address: Long): String? {
        val off = (address - w.start).toInt()
        if (off < 0 || off + 8 > w.bytes.size || !w.mapped[off]) return null
        val parts = ArrayList<String>()
        for (t in listOf(ValueType.INT8, ValueType.INT16, ValueType.INT32, ValueType.INT64, ValueType.FLOAT32, ValueType.FLOAT64)) {
            if ((0 until t.size).all { w.mapped[off + it] }) parts += t.label + " " + ValueCodec.format(t, w.bytes, off)
        }
        return Address.format(address) + ": " + parts.joinToString(" · ")
    }

    fun describe(address: Long): String = c.selectedProcess.value?.regions?.describe(address) ?: ""

    companion object {
        const val WINDOW = 256
        /** 8 bytes per line fits a portrait phone in monospace. */
        const val ROW = 8
    }
}

@Composable
fun HexViewerScreen(initialAddress: Long?, onOpenEditor: (Long) -> Unit) {
    val vm = appViewModel { HexViewerViewModel(it) }
    LaunchedEffect(Unit) { vm.init(initialAddress) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val changed by vm.changed.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val targetInfo by vm.targetInfo.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected

    // Live refresh only while this screen is shown, connected and enabled.
    LaunchedEffect(live, connected) {
        while (live && connected && isActive) {
            vm.refresh()
            delay(vm.intervalMs)
        }
    }

    val highlight = MaterialTheme.colorScheme.tertiary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val targetRow = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            if (selected == null) WarningBanner("No process selected.")
            Text("Read-only view. Tap a line to edit that address.", style = MaterialTheme.typography.bodySmall, color = dim)
            OutlinedTextField(
                value = address, onValueChange = vm::setAddress, label = { Text("Address (hex)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.page(-1) }, enabled = rows.isNotEmpty()) { Text("‹ −256") }
                Button(onClick = vm::go, enabled = selected != null && connected) { Text("Go") }
                OutlinedButton(onClick = { vm.page(1) }, enabled = rows.isNotEmpty()) { Text("+256 ›") }
            }
            LabeledSwitch("Live refresh", live, vm::setLive, "Re-reads every ${vm.intervalMs} ms and highlights bytes that changed.", enabled = connected)
            if (loading && !live) LinearProgressIndicator(Modifier.fillMaxWidth())
            targetInfo?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("targetInfo")) }
            if (rows.isNotEmpty()) Text("$changed byte(s) changed since last read · ${vm.describe(rows.first().address)}", style = MaterialTheme.typography.bodySmall, color = dim)
            ErrorText(error)
        }
        items(rows, key = { it.address }) { row ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = dim)) { append(Address.format(row.address)) }
                    append("  ")
                    for (i in row.bytes.indices) {
                        if (row.changed[i]) withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) { append(row.hex(i)) }
                        else if (!row.mapped[i]) withStyle(SpanStyle(color = dim)) { append(row.hex(i)) }
                        else append(row.hex(i))
                        append(' ')
                    }
                    withStyle(SpanStyle(color = Color.Unspecified)) { append(' '); append(row.ascii()) }
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
                    .then(if (target != null && target!! >= row.address && target!! < row.address + row.bytes.size) Modifier.background(targetRow) else Modifier)
                    .clickable(enabled = row.mapped.any { it }) { onOpenEditor(row.address) }.padding(vertical = 3.dp),
            )
        }
    }
}
