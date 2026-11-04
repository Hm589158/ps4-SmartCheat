package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.Selector
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.pointer.PointerChain
import com.ps4smartcheat.core.pointer.PointerResolution
import com.ps4smartcheat.core.pointer.PointerResolver
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PointerScanViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.backend.state
    val selected = c.selectedProcess
    val state = c.pointerScan.state
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun start(targetText: String, type: ValueType, depth: Int, maxOffset: Int) {
        val t = Address.parse(targetText) ?: run { _message.value = "Enter a hexadecimal target address"; return }
        _message.value = null
        c.pointerScan.start(t, type, depth, maxOffset)
    }

    fun filter(newTargetText: String) {
        val t = Address.parse(newTargetText) ?: run { _message.value = "Enter the value's new address (found with a normal scan)"; return }
        _message.value = null
        c.pointerScan.filter(t)
    }

    fun cancel() = c.pointerScan.cancel()

    fun estimateBytes(): Long? = c.pointerScan.estimateBytes()

    fun add(chain: PointerChain) {
        val sel = c.selectedProcess.value ?: return
        val type = state.value.type
        viewModelScope.launch {
            _message.value = try {
                when (val r = PointerResolver.resolve(c.backend, sel.pid, chain, sel.regions, type.size)) {
                    is PointerResolution.Resolved -> c.addPointerToAddressList(chain, type, r.address)
                    is PointerResolution.Broken -> "Not added: the chain is broken now (${r.reason})"
                }
            } catch (e: BackendException) {
                "Could not follow the chain: ${e.message}"
            }
        }
    }
}

private val depthOptions = listOf(1, 2, 3, 4, 5)
private val offsetOptions = listOf(0x100, 0x400, 0x1000, 0x4000)

@Composable
fun PointerScanScreen(initialAddress: Long?, initialType: ValueType?) {
    val vm = appViewModel { PointerScanViewModel(it) }
    val connection by vm.connection.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf(initialAddress?.let { Address.format(it) } ?: st.target?.let { Address.format(it) } ?: "") }
    var type by remember { mutableStateOf(initialType ?: st.type) }
    var depth by remember { mutableStateOf(3) }
    var maxOffset by remember { mutableStateOf(0x1000) }
    var newTarget by remember { mutableStateOf("") }
    val connected = connection is ConnectionState.Connected
    val alive = selected?.exited == false
    LaunchedEffect(initialAddress) { if (initialAddress != null) target = Address.format(initialAddress) }

    LazyColumn(Modifier.fillMaxSize().testTag("pointerList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val sel = selected
            if (sel == null) WarningBanner("No process selected.")
            else Text("${sel.titleId ?: "no title ID"} — ${sel.name} (pid ${sel.pid})", style = MaterialTheme.typography.titleSmall)
            Text(
                "Finds paths like [[eboot.bin+0x1000]+0x10]+0x48 from the game's static data to a value whose address changes " +
                    "every run. The phone reads all readable data regions once to build a pointer map (read-only, can take a while " +
                    "on big games). Then restart the game, find the value again and filter: chains that still lead to it are stable.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionCard("1. Scan") {
                OutlinedTextField(target, { target = it.trim() }, label = { Text("Target address (hex)") }, singleLine = true, enabled = !st.running, modifier = Modifier.fillMaxWidth().testTag("pointerTarget"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Selector("Value type", ValueType.entries, type, { it.label }, { type = it }, Modifier.weight(1f), enabled = !st.running)
                    Selector("Depth", depthOptions, depth, { "$it" }, { depth = it }, Modifier.weight(0.7f), enabled = !st.running)
                    Selector("Max offset", offsetOptions, maxOffset, { "0x" + Integer.toHexString(it).uppercase() }, { maxOffset = it }, Modifier.weight(1.3f), enabled = !st.running)
                }
                vm.estimateBytes()?.let { b ->
                    Text(
                        "Reads about ${b / (1024 * 1024)} MiB from the PS4 to build the map. Lower \"Max region size\" in Settings to skip huge regions.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("pointerEstimate"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.start(target, type, depth, maxOffset) }, enabled = connected && alive && !st.running, modifier = Modifier.testTag("pointerStart")) { Text("Find pointers") }
                    if (st.running) OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                }
                if (st.running) {
                    val p = st.progress
                    if (p != null) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        (st.stage ?: "") + (p?.let { if (st.stage == "Building pointer map") ": ${it.bytesDone / (1024 * 1024)} of ${it.bytesTotal / (1024 * 1024)} MiB, ${it.matches} pointers" else " — ${it.matches} chain(s)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ErrorText(st.error ?: message?.takeIf { it.startsWith("Enter") })
            }
        }
        if (st.chains.isNotEmpty() || st.target != null && !st.running && st.error == null) {
            item {
                SectionCard("2. After restarting the game") {
                    Text("Find the value again with a normal scan, then enter its new address.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(newTarget, { newTarget = it.trim() }, label = { Text("New address (hex)") }, singleLine = true, enabled = !st.running, modifier = Modifier.fillMaxWidth().testTag("pointerNewTarget"))
                    Button(onClick = { vm.filter(newTarget) }, enabled = connected && alive && !st.running && st.chains.isNotEmpty(), modifier = Modifier.testTag("pointerFilter")) { Text("Keep chains that lead there") }
                }
            }
            item {
                Text(
                    "${st.chains.size} chain(s)" + (st.target?.let { " to ${Address.format(it)}" } ?: "") + if (st.pointerCount > 0L) " · ${st.pointerCount} pointers in map" else "",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("pointerSummary"),
                )
                st.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                message?.takeIf { !it.startsWith("Enter") }?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            }
            itemsIndexed(st.chains.take(300), key = { i, ch -> "$i:${ch.describe()}" }) { i, chain ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("chain-${chain.describe()}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(chain.describe(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text("depth ${chain.depth}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.add(chain) }, enabled = connected && alive, modifier = Modifier.testTag("addChain-${chain.describe()}")) {
                        Icon(Icons.Filled.Add, contentDescription = "Add to address list")
                    }
                }
                HorizontalDivider()
            }
            if (st.chains.size > 300) item { Text("… ${st.chains.size - 300} more. Filter after a restart to narrow them down.") }
        }
    }
}
