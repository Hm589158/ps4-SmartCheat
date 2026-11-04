package com.ps4smartcheat.app.ui.screens

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.cheat.CheatCheck
import com.ps4smartcheat.core.cheat.CheatEntry
import com.ps4smartcheat.core.cheat.CheatFormatException
import com.ps4smartcheat.core.cheat.CheatProfile
import com.ps4smartcheat.core.cheat.GoldHenExporter
import com.ps4smartcheat.core.cheat.ProfileCheck
import com.ps4smartcheat.core.cheat.ProfileValidator
import com.ps4smartcheat.core.edit.EditDeniedException
import com.ps4smartcheat.core.edit.EditTarget
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.edit.WritePlan
import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.scan.ValueParseException
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CheatsViewModel(private val c: AppContainer) : ViewModel() {
    val profiles = c.cheats.profiles
    val selected = c.selectedProcess
    private val _checks = MutableStateFlow<Map<String, ProfileCheck>>(emptyMap())
    val checks = _checks.asStateFlow()
    private val _plan = MutableStateFlow<WritePlan?>(null)
    val plan = _plan.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    /** Pending GoldHEN export content (written once the user picks a file). */
    var pendingGoldHen: String? = null
        private set

    fun validate(profile: CheatProfile) {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select the game process first"; return }
        viewModelScope.launch {
            try {
                val regions = c.backend.getMemoryRegions(sel.pid)
                c.updateRegions(regions)
                val check = ProfileValidator.validate(profile, sel.pid, sel.info, sel.name, regions)
                _checks.value = _checks.value + (profile.id to check)
                _error.value = null
            } catch (e: BackendException) {
                _error.value = e.message
            }
        }
    }

    /** Prepares re-applying a cheat; the write only happens after confirmation. Never automatic. */
    fun prepareApply(check: CheatCheck, profileCheck: ProfileCheck) {
        val sel = c.selectedProcess.value ?: return
        val addr = check.resolvedAddress
        if (!profileCheck.canApply || !check.status.usable || addr == null) {
            _error.value = "Cheat cannot be applied: ${check.status.label}"
            return
        }
        viewModelScope.launch {
            try {
                _plan.value = c.editor.plan(EditTarget(sel.pid, sel.name, addr, check.entry.type), check.entry.replacementValue!!)
            } catch (e: EditDeniedException) {
                _error.value = e.message
            } catch (e: ValueParseException) {
                _error.value = e.message
            } catch (e: BackendException) {
                _error.value = e.message
            }
        }
    }

    fun dismissPlan() { _plan.value = null }

    fun confirmApply() {
        val p = _plan.value ?: return
        _plan.value = null
        viewModelScope.launch {
            val out = c.editor.execute(p)
            if (out is WriteOutcome.Verified) _message.value = "Applied: ${p.replacementText} at ${Address.format(p.target.address)}"
            else _error.value = out.message
        }
    }

    fun deleteProfile(p: CheatProfile) = viewModelScope.launch { c.cheats.deleteProfile(p.id); _checks.value = _checks.value - p.id }
    fun deleteCheat(p: CheatProfile, e: CheatEntry) = viewModelScope.launch { c.cheats.deleteCheat(p.id, e.id) }

    fun updateProfile(p: CheatProfile, name: String, titleId: String, version: String) = viewModelScope.launch {
        if (name.isBlank()) { _error.value = "Profile name is required"; return@launch }
        c.cheats.updateProfile(p.copy(name = name.trim(), titleId = titleId.trim().ifBlank { null }, gameVersion = version.trim().ifBlank { null }))
    }

    fun updateCheat(p: CheatProfile, e: CheatEntry, name: String, value: String) = viewModelScope.launch {
        val v = value.trim().ifBlank { null }
        if (v != null) {
            try { ValueCodec.parse(e.type, v) } catch (ex: ValueParseException) { _error.value = ex.message; return@launch }
        }
        if (name.isBlank()) { _error.value = "Cheat name is required"; return@launch }
        c.cheats.upsertCheat(p.id, e.copy(name = name.trim(), replacementValue = v))
    }

    fun exportTo(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        try {
            val text = c.cheats.exportJson()
            withContext(Dispatchers.IO) { resolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            _message.value = "Exported ${c.cheats.profiles.value.size} profile(s)"
        } catch (e: Exception) {
            _error.value = "Export failed: ${e.message}"
        }
    }

    fun importFrom(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        try {
            val text = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)!!.use { s ->
                    val bytes = s.readBytes()
                    require(bytes.size <= 10 * 1024 * 1024) { "File is too large" }
                    String(bytes, Charsets.UTF_8)
                }
            }
            val r = c.cheats.importJson(text)
            _message.value = "Imported: ${r.added} new, ${r.replaced} replaced"
        } catch (e: CheatFormatException) {
            _error.value = "Import failed: ${e.message}"
        } catch (e: Exception) {
            _error.value = "Import failed: ${e.message}"
        }
    }

    /** Builds GoldHEN JSON; returns the suggested file name if export is possible. */
    fun prepareGoldHen(p: CheatProfile, gameName: String, credits: String): String? {
        val r = GoldHenExporter.export(p, gameName, credits.split(',').map { it.trim() })
        if (r.json == null) {
            _error.value = (r.errors + r.skipped).joinToString("\n")
            return null
        }
        pendingGoldHen = r.json
        _message.value = "GoldHEN export: ${r.exported} cheat(s)" + if (r.skipped.isNotEmpty()) "; skipped:\n" + r.skipped.joinToString("\n") else ""
        return GoldHenExporter.fileName(p)
    }

    fun writeGoldHen(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        val text = pendingGoldHen ?: return@launch
        try {
            withContext(Dispatchers.IO) { resolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            pendingGoldHen = null
        } catch (e: Exception) {
            _error.value = "GoldHEN export failed: ${e.message}"
        }
    }

    fun clearMessages() { _message.value = null; _error.value = null }
}

@Composable
fun CheatsScreen() {
    val vm = appViewModel { CheatsViewModel(it) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver

    var editProfile by remember { mutableStateOf<CheatProfile?>(null) }
    var editCheat by remember { mutableStateOf<Pair<CheatProfile, CheatEntry>?>(null) }
    var deleteProfile by remember { mutableStateOf<CheatProfile?>(null) }
    var goldHenProfile by remember { mutableStateOf<CheatProfile?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(resolver, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(resolver, uri)
    }
    val goldHenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.writeGoldHen(resolver, uri)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
                OutlinedButton(onClick = { exportLauncher.launch("smartcheat-cheats.json") }, enabled = profiles.isNotEmpty()) { Text("Export all") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            ErrorText(error)
            WarningBanner("Saved absolute addresses are only valid for the game session they were found in. Validate a profile before re-applying; nothing is applied automatically.")
            if (profiles.isEmpty()) Text("No cheats saved yet. Save one from the Memory Editor.", modifier = Modifier.padding(top = 8.dp))
        }
        items(profiles, key = { it.id }) { p ->
            val check = checks[p.id]
            val isCurrentGame = selected?.let { it.name == p.processName && (p.titleId == null || it.titleId == null || it.titleId == p.titleId) } == true
            SectionCard(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                p.titleId?.takeIf { it != p.name } ?: if (p.titleId == null) "no title ID" else null,
                                p.gameVersion?.let { "v$it" } ?: "version not set",
                                p.processName,
                                "${p.cheats.size} cheat" + if (p.cheats.size == 1) "" else "s",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { editProfile = p }) { Icon(Icons.Filled.Edit, contentDescription = "Edit profile") }
                    IconButton(onClick = { deleteProfile = p }) { Icon(Icons.Filled.Delete, contentDescription = "Delete profile") }
                }
                if (!isCurrentGame) Text("Not the selected game — validation/apply unavailable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                check?.warnings?.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                p.cheats.forEach { e ->
                    val cc = check?.cheats?.firstOrNull { it.entry.id == e.id }
                    HorizontalDivider()
                    CheatRow(
                        entry = e, check = cc,
                        canApply = check != null && cc != null && check.canApply && cc.status.usable,
                        onApply = { if (cc != null && check != null) vm.prepareApply(cc, check) },
                        onEdit = { editCheat = p to e },
                        onDelete = { vm.deleteCheat(p, e) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.validate(p) }, enabled = isCurrentGame) { Text("Validate") }
                    OutlinedButton(onClick = { goldHenProfile = p }, enabled = p.cheats.isNotEmpty()) { Text("GoldHEN export") }
                }
            }
        }
    }

    plan?.let { pl ->
        ConfirmDialog(
            "Apply cheat",
            "Write ${pl.replacementText} (${pl.target.type.label}) to ${Address.format(pl.target.address)}?" +
                if (pl.warnings.isNotEmpty()) "\n\nWarnings:\n• " + pl.warnings.joinToString("\n• ") else "",
            "Write", vm::confirmApply, vm::dismissPlan,
        )
    }
    deleteProfile?.let { p ->
        ConfirmDialog("Delete profile", "Delete \"${p.name}\" and its ${p.cheats.size} cheat(s)?", "Delete", { vm.deleteProfile(p); deleteProfile = null }, { deleteProfile = null })
    }
    editProfile?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        var title by remember(p.id) { mutableStateOf(p.titleId.orEmpty()) }
        var version by remember(p.id) { mutableStateOf(p.gameVersion.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editProfile = null },
            title = { Text("Edit profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(title, { title = it.uppercase() }, label = { Text("Title ID (e.g. CUSA12345)") }, singleLine = true)
                    OutlinedTextField(version, { version = it }, label = { Text("Game version (e.g. 01.00)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.updateProfile(p, name, title, version); editProfile = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editProfile = null }) { Text("Cancel") } },
        )
    }
    editCheat?.let { (p, e) ->
        var name by remember(e.id) { mutableStateOf(e.name) }
        var value by remember(e.id) { mutableStateOf(e.replacementValue.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editCheat = null },
            title = { Text("Edit cheat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(value, { value = it }, label = { Text("Replacement value (${e.type.label})") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.updateCheat(p, e, name, value); editCheat = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editCheat = null }) { Text("Cancel") } },
        )
    }
    goldHenProfile?.let { p ->
        var gameName by remember(p.id) { mutableStateOf(p.name) }
        var credits by remember(p.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { goldHenProfile = null },
            title = { Text("Export GoldHEN cheat file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Requires title ID and game version on the profile. Only cheats inside ${p.processName} with a recorded original value are exported. " +
                            "GoldHEN offset semantics follow PS4CheaterNeo and are not yet verified on hardware.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(gameName, { gameName = it }, label = { Text("Game name") }, singleLine = true)
                    OutlinedTextField(credits, { credits = it }, label = { Text("Credits (comma separated)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val fileName = vm.prepareGoldHen(p, gameName, credits)
                    goldHenProfile = null
                    if (fileName != null) goldHenLauncher.launch(fileName)
                }) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { goldHenProfile = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CheatRow(entry: CheatEntry, check: CheatCheck?, canApply: Boolean, onApply: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            val where = entry.module?.let { "${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}" } ?: Address.format(entry.address)
            Text("$where · ${entry.type.label} → ${entry.replacementValue ?: "—"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (check != null) {
                Text(
                    check.status.label + (check.resolvedAddress?.let { " @ ${Address.format(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (check.status.usable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = onApply, enabled = canApply) { Text(if (check == null) "Validate first" else "Apply") }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit cheat") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete cheat") }
    }
}

