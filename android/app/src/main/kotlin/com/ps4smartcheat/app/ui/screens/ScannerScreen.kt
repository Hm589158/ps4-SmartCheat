package com.ps4smartcheat.app.ui.screens

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.LabeledSwitch
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
import com.ps4smartcheat.core.scan.describe
import com.ps4smartcheat.core.tools.ScanResultsCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScannerViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.backend.state
    val selected = c.selectedProcess
    val status = c.scanner.status
    val session = c.scanner.session
    val stats = c.scanner.lastStats

    private val _type = MutableStateFlow(ValueType.INT32)
    val type = _type.asStateFlow()
    private val _firstMode = MutableStateFlow(ScanMode.EXACT)
    val firstMode = _firstMode.asStateFlow()
    private val _nextMode = MutableStateFlow(ScanMode.EXACT)
    val nextMode = _nextMode.asStateFlow()
    private val _value = MutableStateFlow("")
    val value = _value.asStateFlow()
    private val _value2 = MutableStateFlow("")
    /** Upper bound for "Value between". */
    val value2 = _value2.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _page = MutableStateFlow(0)
    val page = _page.asStateFlow()
    private val _rows = MutableStateFlow<List<ResultRow>>(emptyList())
    val rows = _rows.asStateFlow()
    private val _rowsLoading = MutableStateFlow(false)
    val rowsLoading = _rowsLoading.asStateFlow()

    val pageSize: Int get() = c.settings.value.pageSize.coerceIn(10, 200)
    val intervalMs: Long get() = c.settings.value.monitorIntervalMs.coerceIn(250, 10_000).toLong()

    private val _live = MutableStateFlow(false)
    /** Periodically re-reads the visible result page (value monitoring). */
    val live = _live.asStateFlow()
    fun setLive(v: Boolean) { _live.value = v }

    /** Live refresh tick: skipped while a scan or a page load is running. */
    fun liveTick() {
        if (c.scanner.isRunning || _rowsLoading.value) return
        loadPage()
    }

    fun exportCsv(resolver: ContentResolver, uri: Uri) {
        val res = c.scanner.results() ?: run { _error.value = "Nothing to export"; return }
        val sel = c.selectedProcess.value
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
                        ScanResultsCsv.write(w, res, sel?.regions ?: emptyList(), sel?.name)
                    }
                }
                c.log.notify("Exported $n result(s) to CSV")
            } catch (e: Exception) {
                _error.value = "Export failed: ${e.message}"
            }
        }
    }

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
    fun setValue2(v: String) { _value2.value = v; _error.value = null }

    fun firstScan() {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select a process first"; return }
        val s = c.settings.value
        try {
            c.scanner.firstScan(sel.pid, sel.name, _type.value, _firstMode.value, _value.value, s.regionFilter(), s.scanOptions(), valueText2 = _value2.value)
            _rows.value = emptyList()
            c.log.info("First scan ${_type.value.label} ${_firstMode.value.label} '${_value.value}'" + (if (_firstMode.value.needsSecondValue) "..'${_value2.value}'" else "") + " in ${sel.name}")
        } catch (e: IllegalArgumentException) {
            _error.value = e.message
        } catch (e: IllegalStateException) {
            _error.value = e.message
        }
    }

    fun nextScan() {
        try {
            c.scanner.nextScan(_nextMode.value, _value.value, _value2.value)
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

    fun addToList(row: ResultRow) {
        viewModelScope.launch { c.log.notify(c.addToAddressList(row.address, row.type)) }
    }

    /** Region description for a result address (e.g. "eboot.bin+0x120 [rw-]"). */
    fun describe(address: Long): String = c.selectedProcess.value?.regions?.describe(address) ?: ""

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
fun ScannerScreen(onEdit: (Long, ValueType) -> Unit, onPatternSearch: () -> Unit = {}) {
    val vm = appViewModel { ScannerViewModel(it) }
    val connection by vm.connection.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val firstMode by vm.firstMode.collectAsStateWithLifecycle()
    val nextMode by vm.nextMode.collectAsStateWithLifecycle()
    val value by vm.value.collectAsStateWithLifecycle()
    val value2 by vm.value2.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val rowsLoading by vm.rowsLoading.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(resolver, uri)
    }

    val running = status is ScanStatus.Running
    val connected = connection is ConnectionState.Connected
    val hasSession = session != null && session?.pid == selected?.pid
    val mode = if (hasSession) nextMode else firstMode
    val needsValue = mode.needsValue

    LaunchedEffect(selected?.pid) { if (hasSession) vm.loadPage() }
    LaunchedEffect(live, connected, hasSession) {
        while (live && connected && hasSession && isActive) {
            delay(vm.intervalMs)
            vm.liveTick()
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("scannerList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val sel = selected
            if (sel == null) {
                WarningBanner("No process selected. Pick the game on the Processes screen.")
            } else if (sel.exited) {
                WarningBanner("${sel.exitedReason}. Scanning is disabled until the game runs again.")
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
                    val kb = KeyboardOptions(keyboardType = if (type.isFloat) KeyboardType.Decimal else KeyboardType.Text)
                    if (mode.needsSecondValue) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = value, onValueChange = vm::setValue, singleLine = true, enabled = !running,
                                label = { Text("From") }, keyboardOptions = kb, modifier = Modifier.weight(1f).testTag("scanValue"),
                            )
                            OutlinedTextField(
                                value = value2, onValueChange = vm::setValue2, singleLine = true, enabled = !running,
                                label = { Text("To") }, keyboardOptions = kb, modifier = Modifier.weight(1f).testTag("scanValue2"),
                            )
                        }
                        Text("Inclusive range. Range scans are compared on Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        OutlinedTextField(
                            value = value, onValueChange = vm::setValue, singleLine = true, enabled = !running,
                            label = {
                                Text(
                                    when {
                                        mode == ScanMode.EXACT && type.isFloat -> "Value (decimals control rounding)"
                                        type.isFloat -> "Value"
                                        else -> "Value (decimal or 0x hex)"
                                    },
                                )
                            },
                            keyboardOptions = kb,
                            modifier = Modifier.fillMaxWidth().testTag("scanValue"),
                        )
                    }
                }
                ErrorText(error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::firstScan, enabled = connected && selected != null && selected?.exited != true && !running, modifier = Modifier.testTag("firstScan")) { Text(if (hasSession) "New scan" else "First scan") }
                    Button(onClick = vm::nextScan, enabled = connected && hasSession && !running, modifier = Modifier.testTag("nextScan")) { Text("Next scan") }
                    if (running) OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    else if (hasSession) TextButton(onClick = vm::reset) { Text("Reset") }
                }
                TextButton(onClick = onPatternSearch, enabled = !running, modifier = Modifier.testTag("openPatternSearch")) { Text("Byte pattern / text search…") }
                when (val st = status) {
                    is ScanStatus.Running -> {
                        val p = st.progress
                        if (p.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            buildString {
                                append(p.stage)
                                if (!p.indeterminate) append(": ${p.bytesDone.formatProgress(p.stage)} of ${p.bytesTotal.formatProgress(p.stage)}")
                                append(" — ${p.matches} matches")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${p.backend?.label ?: "…"} · ${formatMs(p.elapsedMs)}" +
                                (p.mibPerSecond?.let { " · %.1f MiB/s".format(it) } ?: "") + " · net ${formatBytes(p.networkBytes)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ScanStatus.Completed -> {
                        if (!hasSession) Text(st.message, style = MaterialTheme.typography.bodySmall)
                        stats?.let { ss ->
                            Text(
                                "${ss.backend.label} · ${formatMs(ss.durationMs)}" + (ss.mibPerSecond?.let { " · %.1f MiB/s".format(it) } ?: "") +
                                    " · net ${formatBytes(ss.traffic.total)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ss.fallbacks.forEach { Text("↳ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    is ScanStatus.Failed -> Text("Failed: ${st.message}", color = MaterialTheme.colorScheme.error)
                    ScanStatus.Cancelled -> Text("Cancelled — previous results kept.", style = MaterialTheme.typography.bodySmall)
                    is ScanStatus.Ended -> Text("Scan session closed: ${st.reason}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    ScanStatus.Idle -> {}
                }
            }
        }
        val s = session
        if (hasSession && s != null) {
            item {
                Text(
                    "Scan ${s.step}: ${if (s.snapshotCandidates != null) "${s.snapshotCandidates} candidates in snapshot" else plural(s.resultCount.toLong(), "result")}" +
                        (s.serverTotal?.takeIf { it > s.resultCount }?.let { " ($it kept on PS4)" } ?: "") +
                        if (s.truncated) " (list capped)" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (s.truncated && (s.serverTotal ?: 0) > s.resultCount) {
                    Text("The PS4 still holds all ${s.serverTotal} candidates: the next Turbo scan narrows the full set.", style = MaterialTheme.typography.bodySmall)
                } else if (s.truncated) WarningBanner("Too many matches: only the first ${s.resultCount} were kept. Refine with a more specific value or a narrower region filter.")
            }
            if (s.resultCount > 0) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.goToPage(page - 1) }, enabled = page > 0 && !rowsLoading) { Text("‹ Prev") }
                        Text("Page ${page + 1} / ${vm.pageCount()}")
                        TextButton(onClick = { vm.goToPage(page + 1) }, enabled = page + 1 < vm.pageCount() && !rowsLoading) { Text("Next ›") }
                        TextButton(onClick = vm::loadPage, enabled = !rowsLoading && connected) { Text("Refresh") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabeledSwitch("Live values", live, vm::setLive, "Re-read this page every ${vm.intervalMs} ms", enabled = connected)
                    }
                    TextButton(onClick = { csvLauncher.launch("smartcheat-results.csv") }, enabled = !running) { Text("Export results (CSV)") }
                    if (rowsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                items(rows, key = { it.index }) { row ->
                    ResultRowView(row, vm.describe(row.address), onAdd = { vm.addToList(row) }) { onEdit(row.address, row.type) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ResultRowView(row: ResultRow, region: String, onAdd: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("result-" + Address.format(row.address)).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.3f)) {
            Text(Address.format(row.address), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Text(region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            val cur = row.current?.let { ValueCodec.format(row.type, it) } ?: "?"
            Text(cur, fontFamily = FontFamily.Monospace, color = if (row.changed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
            Text("prev ${ValueCodec.format(row.type, row.previous)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onAdd, modifier = Modifier.testTag("add-" + Address.format(row.address))) {
            Icon(Icons.Filled.Add, contentDescription = "Add to address list")
        }
    }
}

private fun plural(n: Long, word: String): String = "$n $word" + if (n == 1L) "" else "s"

private fun formatMs(ms: Long): String = if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KiB".format(b / 1024.0)
    else -> "%.1f MiB".format(b / 1048576.0)
}

private fun Long.formatProgress(stage: String): String =
    if (stage == "Refining") "$this" else "${this / (1024 * 1024)} MiB"
