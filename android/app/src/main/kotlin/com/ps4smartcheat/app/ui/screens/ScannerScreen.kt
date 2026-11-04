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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.Selector
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.scan.ResultRow
import com.ps4smartcheat.core.scan.ScanMode
import com.ps4smartcheat.core.scan.ScanStatus
import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScannerViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.backend.state
    val selected = c.selectedProcess
    val status = c.scanner.status
    val session = c.scanner.session

    private val _type = MutableStateFlow(ValueType.INT32)
    val type = _type.asStateFlow()
    private val _firstMode = MutableStateFlow(ScanMode.EXACT)
    val firstMode = _firstMode.asStateFlow()
    private val _nextMode = MutableStateFlow(ScanMode.EXACT)
    val nextMode = _nextMode.asStateFlow()
    private val _value = MutableStateFlow("")
    val value = _value.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _page = MutableStateFlow(0)
    val page = _page.asStateFlow()
    private val _rows = MutableStateFlow<List<ResultRow>>(emptyList())
    val rows = _rows.asStateFlow()
    private val _rowsLoading = MutableStateFlow(false)
    val rowsLoading = _rowsLoading.asStateFlow()

    val pageSize: Int get() = c.settings.value.pageSize.coerceIn(10, 200)

    init {
        viewModelScope.launch {
            c.scanner.status.collect { st ->
                when (st) {
                    is ScanStatus.Completed -> { _page.value = 0; loadPage() }
                    is ScanStatus.Failed -> c.log.error("Scan failed: ${st.message}")
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            c.scanner.session.collect { s -> if (s != null) _type.value = s.type }
        }
    }

    fun setType(t: ValueType) { _type.value = t; _error.value = null }
    fun setFirstMode(m: ScanMode) { _firstMode.value = m; _error.value = null }
    fun setNextMode(m: ScanMode) { _nextMode.value = m; _error.value = null }
    fun setValue(v: String) { _value.value = v; _error.value = null }

    fun firstScan() {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select a process first"; return }
        val s = c.settings.value
        try {
            c.scanner.firstScan(sel.pid, sel.name, _type.value, _firstMode.value, _value.value, s.regionFilter(), s.scanOptions())
            _rows.value = emptyList()
            c.log.info("First scan ${_type.value.label} ${_firstMode.value.label} '${_value.value}' in ${sel.name}")
        } catch (e: IllegalArgumentException) {
            _error.value = e.message
        } catch (e: IllegalStateException) {
            _error.value = e.message
        }
    }

    fun nextScan() {
        try {
            c.scanner.nextScan(_nextMode.value, _value.value)
            c.log.info("Next scan ${_nextMode.value.label} '${_value.value}'")
        } catch (e: IllegalArgumentException) {
            _error.value = e.message
        } catch (e: IllegalStateException) {
            _error.value = e.message
        }
    }

    fun cancel() = c.scanner.cancel()

    fun reset() {
        c.selectedProcess.value?.let { c.scanner.reset(it.pid) }
        _rows.value = emptyList()
        _page.value = 0
    }

    fun pageCount(): Int = c.scanner.results()?.pageCountFor?.invoke(pageSize) ?: 0

    fun goToPage(p: Int) {
        val count = pageCount()
        if (count == 0) return
        _page.value = p.coerceIn(0, count - 1)
        loadPage()
    }

    fun loadPage() {
        viewModelScope.launch {
            _rowsLoading.value = true
            try {
                _rows.value = c.scanner.readPage(_page.value, pageSize)
            } catch (e: Exception) {
                _error.value = "Could not read values: ${e.message}"
            } finally {
                _rowsLoading.value = false
            }
        }
    }
}

@Composable
fun ScannerScreen(onEdit: (Long, ValueType) -> Unit) {
    val vm = appViewModel { ScannerViewModel(it) }
    val connection by vm.connection.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val firstMode by vm.firstMode.collectAsStateWithLifecycle()
    val nextMode by vm.nextMode.collectAsStateWithLifecycle()
    val value by vm.value.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val rowsLoading by vm.rowsLoading.collectAsStateWithLifecycle()

    val running = status is ScanStatus.Running
    val connected = connection is ConnectionState.Connected
    val hasSession = session != null && session?.pid == selected?.pid
    val mode = if (hasSession) nextMode else firstMode
    val needsValue = mode.needsValue

    LaunchedEffect(selected?.pid) { if (hasSession) vm.loadPage() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val sel = selected
            if (sel == null) {
                WarningBanner("No process selected. Pick the game on the Processes screen.")
            } else {
                Text("Process: ${sel.name} (pid ${sel.pid})", style = MaterialTheme.typography.titleSmall)
            }
            if (!connected) Text("Not connected.", color = MaterialTheme.colorScheme.error)
        }
        item {
            SectionCard(null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Selector("Value type", ValueType.entries, type, { it.label }, vm::setType, Modifier.weight(1f), enabled = !running && !hasSession)
                    if (hasSession) {
                        Selector("Next scan", ScanMode.NEXT_SCAN_MODES, nextMode, { it.label }, vm::setNextMode, Modifier.weight(1.4f), enabled = !running)
                    } else {
                        Selector("First scan", ScanMode.FIRST_SCAN_MODES, firstMode, { it.label }, vm::setFirstMode, Modifier.weight(1.4f), enabled = !running)
                    }
                }
                if (needsValue) {
                    OutlinedTextField(
                        value = value, onValueChange = vm::setValue, singleLine = true, enabled = !running,
                        label = { Text(if (type.isFloat) "Value (decimals control rounding)" else "Value (decimal or 0x hex)") },
                        keyboardOptions = KeyboardOptions(keyboardType = if (type.isFloat) KeyboardType.Decimal else KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ErrorText(error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::firstScan, enabled = connected && selected != null && !running) { Text(if (hasSession) "New scan" else "First scan") }
                    Button(onClick = vm::nextScan, enabled = connected && hasSession && !running) { Text("Next scan") }
                    if (running) OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    else if (hasSession) TextButton(onClick = vm::reset) { Text("Reset") }
                }
                when (val st = status) {
                    is ScanStatus.Running -> {
                        LinearProgressIndicator(progress = { st.progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${st.progress.stage}: ${st.progress.bytesDone.formatProgress(st.progress.stage)} of ${st.progress.bytesTotal.formatProgress(st.progress.stage)} — ${st.progress.matches} matches", style = MaterialTheme.typography.bodySmall)
                    }
                    is ScanStatus.Completed -> Text(st.message, style = MaterialTheme.typography.bodySmall)
                    is ScanStatus.Failed -> Text("Failed: ${st.message}", color = MaterialTheme.colorScheme.error)
                    ScanStatus.Cancelled -> Text("Cancelled — previous results kept.", style = MaterialTheme.typography.bodySmall)
                    ScanStatus.Idle -> {}
                }
            }
        }
        val s = session
        if (hasSession && s != null) {
            item {
                Text(
                    "Scan ${s.step}: ${if (s.snapshotCandidates != null) "${s.snapshotCandidates} candidates in snapshot" else "${s.resultCount} results"}" +
                        if (s.truncated) " (capped — incomplete)" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (s.truncated) WarningBanner("Too many matches: only the first ${s.resultCount} were kept. Refine with a more specific value or a narrower region filter.")
            }
            if (s.resultCount > 0) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.goToPage(page - 1) }, enabled = page > 0 && !rowsLoading) { Text("‹ Prev") }
                        Text("Page ${page + 1} / ${vm.pageCount()}")
                        TextButton(onClick = { vm.goToPage(page + 1) }, enabled = page + 1 < vm.pageCount() && !rowsLoading) { Text("Next ›") }
                        TextButton(onClick = vm::loadPage, enabled = !rowsLoading && connected) { Text("Refresh") }
                    }
                    if (rowsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                items(rows, key = { it.index }) { row ->
                    ResultRowView(row) { onEdit(row.address, row.type) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ResultRowView(row: ResultRow, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Address.format(row.address), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f)) {
            val cur = row.current?.let { ValueCodec.format(row.type, it) } ?: "?"
            Text(cur, fontFamily = FontFamily.Monospace, color = if (row.changed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
            Text("prev ${ValueCodec.format(row.type, row.previous)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Long.formatProgress(stage: String): String =
    if (stage == "Refining") "$this" else "${this / (1024 * 1024)} MiB"
