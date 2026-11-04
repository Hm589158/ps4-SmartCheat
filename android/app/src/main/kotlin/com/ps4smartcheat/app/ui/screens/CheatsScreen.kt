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

    // ------------------------------------------------------------------ GoldHEN cheat files (session only)

    private val _gh = MutableStateFlow<com.ps4smartcheat.core.cheat.GhImportFile?>(null)
    /** GoldHEN file opened in this session (not stored). */
    val gh = _gh.asStateFlow()
    private val _ghChecks = MutableStateFlow<Map<Int, com.ps4smartcheat.core.cheat.ModCheck>>(emptyMap())
    val ghChecks = _ghChecks.asStateFlow()
    private val _ghMismatch = MutableStateFlow<String?>(null)
    val ghMismatch = _ghMismatch.asStateFlow()

    /** Toggle waiting for confirmation: mod index, enable?, one verified-write plan per patch. */
    data class GhToggle(val index: Int, val enable: Boolean, val plans: List<WritePlan>)
    private val _ghToggle = MutableStateFlow<GhToggle?>(null)
    val ghToggle = _ghToggle.asStateFlow()

    fun openGoldHen(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        try {
            val text = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)!!.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (out.size() <= 2 * 1024 * 1024) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.toString("UTF-8")
                }
            }
            openGoldHenText(text)
        } catch (e: Exception) {
            _error.value = "Could not open the file: ${e.message}"
        }
    }

    /** Parses a GoldHEN file and checks it against the selected game. */
    fun openGoldHenText(text: String) {
        try {
            _gh.value = com.ps4smartcheat.core.cheat.GoldHenImport.parse(text)
            _ghChecks.value = emptyMap()
            _error.value = null
            checkGoldHen()
        } catch (e: com.ps4smartcheat.core.cheat.GoldHenImport.FormatException) {
            _error.value = e.message
        }
    }

    fun closeGoldHen() { _gh.value = null; _ghChecks.value = emptyMap(); _ghMismatch.value = null }

    /** Reads the bytes of every applicable mod in the selected game (read-only). */
    fun checkGoldHen() {
        val f = _gh.value ?: return
        val sel = c.selectedProcess.value?.takeIf { !it.exited } ?: run { _ghMismatch.value = "Select the running game to check this file."; return }
        _ghMismatch.value = com.ps4smartcheat.core.cheat.GoldHenImport.mismatch(f, sel.titleId, sel.name)
        if (_ghMismatch.value != null) { _ghChecks.value = emptyMap(); return }
        viewModelScope.launch {
            try {
                val regions = c.backend.getMemoryRegions(sel.pid)
                _ghChecks.value = f.mods.withIndex().filter { it.value.applicable }.associate { (i, m) ->
                    i to com.ps4smartcheat.core.cheat.GoldHenImport.check(c.backend, sel.pid, f, m, regions)
                }
            } catch (e: BackendException) {
                _error.value = e.message
            }
        }
    }

    fun prepareGoldHenToggle(index: Int, enable: Boolean) {
        val f = _gh.value ?: return
        val sel = c.selectedProcess.value ?: return
        viewModelScope.launch {
            try {
                val regions = c.backend.getMemoryRegions(sel.pid)
                val check = com.ps4smartcheat.core.cheat.GoldHenImport.check(c.backend, sel.pid, f, f.mods[index], regions)
                _ghChecks.value = _ghChecks.value + (index to check)
                if (if (enable) !check.canEnable else !check.canDisable) {
                    _error.value = "\"${f.mods[index].name}\" is ${check.states.joinToString { it.label }} — nothing written."
                    return@launch
                }
                val plans = check.mod.patches.mapIndexed { i, p -> c.editor.planBytes(sel.pid, sel.name, check.addresses[i], if (enable) p.on else p.off) }
                _ghToggle.value = GhToggle(index, enable, plans)
            } catch (e: EditDeniedException) {
                _error.value = e.message
            } catch (e: BackendException) {
                _error.value = e.message
            }
        }
    }

    fun dismissGoldHenToggle() { _ghToggle.value = null }

    fun confirmGoldHenToggle() {
        val t = _ghToggle.value ?: return
        _ghToggle.value = null
        val name = _gh.value?.mods?.getOrNull(t.index)?.name ?: "mod"
        viewModelScope.launch {
            for (p in t.plans) {
                val out = c.editor.execute(p)
                if (out !is WriteOutcome.Verified) {
                    _error.value = "\"$name\": ${out.message} (stopped; earlier patches of this mod were written — use Undo in the editor)"
                    checkGoldHen()
                    return@launch
                }
            }
            _message.value = "\"$name\" ${if (t.enable) "enabled" else "disabled"}: ${t.plans.size} patch(es) verified"
            c.log.info("GoldHEN mod $name ${if (t.enable) "on" else "off"}")
            checkGoldHen()
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
    val gh by vm.gh.collectAsStateWithLifecycle()
    val ghChecks by vm.ghChecks.collectAsStateWithLifecycle()
    val ghMismatch by vm.ghMismatch.collectAsStateWithLifecycle()
    val ghToggle by vm.ghToggle.collectAsStateWithLifecycle()
    val ghOpen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openGoldHen(resolver, uri)
    }

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
            OutlinedButton(onClick = { ghOpen.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Open GoldHEN file…") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            ErrorText(error)
            WarningBanner("Saved absolute addresses are only valid for the game session they were found in. Validate a profile before re-applying; nothing is applied automatically.")
            if (profiles.isEmpty()) Text("No cheats saved yet. Save one from the Memory Editor.", modifier = Modifier.padding(top = 8.dp))
        }
        gh?.let { f ->
            item { GoldHenCard(f, ghChecks, ghMismatch, canWrite = selected?.exited == false, onCheck = vm::checkGoldHen, onClose = vm::closeGoldHen, onToggle = vm::prepareGoldHenToggle) }
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

    ghToggle?.let { t ->
        val name = gh?.mods?.getOrNull(t.index)?.name ?: "mod"
        ConfirmDialog(
            title = if (t.plans.any { it.dangerous }) "Confirm risky patches" else if (t.enable) "Enable cheat" else "Disable cheat",
            text = buildString {
                append("${if (t.enable) "Enable" else "Disable"} \"$name\" (${t.plans.size} patch(es))?\n")
                t.plans.forEach { p -> append("\n${Address.format(p.target.address)}: ${com.ps4smartcheat.core.util.Hex.encode(p.original, " ")} → ${p.replacementText}") }
                val w = t.plans.flatMap { it.warnings }.distinct()
                if (w.isNotEmpty()) append("\n\nWarnings:\n• " + w.joinToString("\n• "))
            },
            confirmLabel = if (t.enable) "Enable" else "Disable",
            onConfirm = vm::confirmGoldHenToggle,
            onDismiss = vm::dismissGoldHenToggle,
        )
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


@Composable
private fun GoldHenCard(
    f: com.ps4smartcheat.core.cheat.GhImportFile,
    checks: Map<Int, com.ps4smartcheat.core.cheat.ModCheck>,
    mismatch: String?,
    canWrite: Boolean,
    onCheck: () -> Unit,
    onClose: () -> Unit,
    onToggle: (Int, Boolean) -> Unit,
) {
    SectionCard("GoldHEN file: ${f.name.ifBlank { "unnamed" }}") {
        Text("${f.titleId.ifBlank { "no title ID" }} · v${f.version.ifBlank { "?" }} · ${f.process} · ${f.mods.size} mod(s)", style = MaterialTheme.typography.bodySmall)
        Text(
            "Offsets are read as relative to the start of ${f.process} (PS4CheaterNeo's interpretation; unverified on hardware). " +
                "A mod is only enabled when the memory holds exactly its \"off\" bytes, so files for another game version are refused.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        f.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        mismatch?.let { WarningBanner(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheck, modifier = Modifier.testTag("ghCheck")) { Text("Check") }
            TextButton(onClick = onClose) { Text("Close") }
        }
        f.mods.forEachIndexed { i, m ->
            HorizontalDivider()
            val ck = checks[i]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("ghMod-$i")) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.bodyMedium)
                    val status = when {
                        !m.applicable -> "not applicable: " + m.problems.joinToString("; ")
                        ck == null -> "${m.patches.size} patch(es) — not checked"
                        else -> "${m.patches.size} patch(es) — " + (ck.state?.label ?: ck.states.joinToString { it.label })
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (m.applicable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary)
                }
                if (ck != null && ck.canEnable) Button(onClick = { onToggle(i, true) }, enabled = canWrite, modifier = Modifier.testTag("ghEnable-$i")) { Text("Enable") }
                if (ck != null && ck.canDisable) OutlinedButton(onClick = { onToggle(i, false) }, enabled = canWrite, modifier = Modifier.testTag("ghDisable-$i")) { Text("Disable") }
            }
        }
    }
}
