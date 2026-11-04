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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
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
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.scan.RegionFilter
import com.ps4smartcheat.core.scan.ScanProgress
import com.ps4smartcheat.core.scan.describe
import com.ps4smartcheat.core.tools.BytePattern
import com.ps4smartcheat.core.tools.PatternParseException
import com.ps4smartcheat.core.tools.PatternSearch
import com.ps4smartcheat.core.tools.PatternSearchResult
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PatternKind(val label: String) { BYTES("Byte pattern (AOB)"), TEXT("Text") }

class PatternSearchViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.backend.state
    val selected = c.selectedProcess

    val kind = MutableStateFlow(PatternKind.BYTES)
    val input = MutableStateFlow("")
    val encoding = MutableStateFlow(BytePattern.Companion.TextEncoding.UTF8)
    val ignoreCase = MutableStateFlow(true)
    /** Code patterns live in executable regions and strings often in read-only data: both included by default. */
    val includeReadOnly = MutableStateFlow(true)
    val includeExecutable = MutableStateFlow(true)

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress = _progress.asStateFlow()
    private val _result = MutableStateFlow<PatternSearchResult?>(null)
    val result = _result.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    fun search() {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select a process first"; return }
        if (sel.exited) { _error.value = "${sel.name} is no longer running"; return }
        val pattern = try {
            when (kind.value) {
                PatternKind.BYTES -> BytePattern.hex(input.value)
                PatternKind.TEXT -> BytePattern.text(input.value, encoding.value, ignoreCase.value)
            }
        } catch (e: PatternParseException) {
            _error.value = e.message; return
        }
        if (job?.isActive == true) return
        _error.value = null
        _result.value = null
        _running.value = true
        job = viewModelScope.launch {
            try {
                val s = c.settings.value
                val filter = RegionFilter(includeReadOnly.value, includeExecutable.value, s.maxRegionMiB.coerceAtLeast(0).toLong() * 1024 * 1024)
                val regions = filter.select(c.backend.getMemoryRegions(sel.pid))
                if (regions.isEmpty()) { _error.value = "No memory regions match the region filter"; return@launch }
                val res = PatternSearch.search(c.backend, sel.pid, regions, pattern, maxHits = MAX_HITS) { _progress.value = it }
                _result.value = res
                c.log.info("Pattern search ${res.pattern}: ${res.hits.size} hit(s)" + if (res.truncated) " (capped)" else "")
            } catch (e: CancellationException) {
                _error.value = "Cancelled"
                throw e
            } catch (e: BackendException) {
                _error.value = "Search failed: ${e.message}"
            } finally {
                _running.value = false
                _progress.value = null
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun describe(address: Long): String = c.selectedProcess.value?.regions?.describe(address) ?: ""

    companion object {
        const val MAX_HITS = 5_000
    }
}

@Composable
fun PatternSearchScreen(onOpen: (Long) -> Unit) {
    val vm = appViewModel { PatternSearchViewModel(it) }
    val connection by vm.connection.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val kind by vm.kind.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val encoding by vm.encoding.collectAsStateWithLifecycle()
    val ignoreCase by vm.ignoreCase.collectAsStateWithLifecycle()
    val ro by vm.includeReadOnly.collectAsStateWithLifecycle()
    val ex by vm.includeExecutable.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected

    LazyColumn(Modifier.fillMaxSize().testTag("patternList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val sel = selected
            if (sel == null) WarningBanner("No process selected.")
            else Text("Process: ${sel.name} (pid ${sel.pid})", style = MaterialTheme.typography.titleSmall)
            SectionCard(null) {
                Selector("Search for", PatternKind.entries, kind, { it.label }, { vm.kind.value = it }, enabled = !running)
                OutlinedTextField(
                    input, { vm.input.value = it }, singleLine = true, enabled = !running,
                    label = { Text(if (kind == PatternKind.BYTES) "Bytes, e.g. 48 8B ?? ?? 89 05" else "Text") },
                    modifier = Modifier.fillMaxWidth().testTag("patternInput"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (kind == PatternKind.BYTES) FontFamily.Monospace else FontFamily.Default),
                )
                if (kind == PatternKind.TEXT) {
                    Selector("Encoding", BytePattern.Companion.TextEncoding.entries, encoding, { it.label }, { vm.encoding.value = it }, enabled = !running)
                    LabeledSwitch("Ignore case", ignoreCase, { vm.ignoreCase.value = it }, "ASCII letters only", enabled = !running)
                } else {
                    Text("?? matches any byte; 4? / ?F match one nibble.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LabeledSwitch("Include read-only regions", ro, { vm.includeReadOnly.value = it }, enabled = !running)
                LabeledSwitch("Include executable regions", ex, { vm.includeExecutable.value = it }, "Code patterns are in executable regions", enabled = !running)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::search, enabled = connected && selected?.exited == false && !running, modifier = Modifier.testTag("patternSearch")) { Text("Search") }
                    if (running) OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                }
                progress?.let { p ->
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${p.bytesDone / (1024 * 1024)} of ${p.bytesTotal / (1024 * 1024)} MiB — ${p.matches} hit(s)", style = MaterialTheme.typography.bodySmall)
                }
                ErrorText(error)
                Text("Read-only: searching never writes to the PS4. Runs on Android over CMD_PROC_READ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        result?.let { res ->
            item {
                Text(
                    "${res.hits.size} hit(s) for ${res.pattern}" + if (res.truncated) " — capped at ${PatternSearchViewModel.MAX_HITS}, make the pattern more specific" else "",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("patternSummary"),
                )
            }
            items(res.hits.take(500), key = { it.address }) { hit ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(hit.address) }.padding(vertical = 6.dp).testTag("hit-" + Address.format(hit.address))) {
                    Text(Address.format(hit.address), fontFamily = FontFamily.Monospace)
                    Text(vm.describe(hit.address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Hex.encode(hit.bytes.copyOf(minOf(hit.bytes.size, 16)), " "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            if (res.hits.size > 500) item { Text("… ${res.hits.size - 500} more not shown") }
        }
    }
}
