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
import com.ps4smartcheat.core.cheat.fingerprint
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

    fun closeGoldHen() { _gh.value = null; _ghChecks.value = emptyMap(); _ghMismatch.value = null; _ghLibrary.value = false; ghProfileId = null }

    /** Profile whose code patches the ON/OFF card shows (library mode). */
    private var ghProfileId: String? = null

    init {
        // The card must never show a profile as it was when it was opened: a cheat saved, edited or deleted meanwhile
        // changes the bytes that are compared with memory.
        viewModelScope.launch {
            c.cheats.profiles.collect { if (_ghLibrary.value && ghProfileId != null) checkGoldHen() }
        }
    }

    /** Rebuilds the card's mods from the profile as it is stored now. False when it has no code patches any more. */
    private fun reloadLibraryFile(): Boolean {
        val id = ghProfileId ?: return true
        val f = c.cheats.profile(id)?.let { com.ps4smartcheat.core.cheat.CodeCheats.asGoldHenFile(it) }
        if (f == null) { closeGoldHen(); return false }
        if (f.fingerprint() != _gh.value?.fingerprint()) { _gh.value = f; _ghChecks.value = emptyMap() }
        return true
    }

    /** Called whenever the Cheats screen is shown: an open ON/OFF card is checked against memory again. */
    fun onShown() { if (_gh.value != null) checkGoldHen() }

    private val _ghLibrary = MutableStateFlow(false)
    /** True when the ON/OFF card shows code patches of a saved profile (not an opened GoldHEN file). */
    val ghLibrary = _ghLibrary.asStateFlow()

    /** Shows the profile's code patches as ON/OFF switches (same checks and verified writes as GoldHEN mods). */
    fun openCodePatches(p: CheatProfile) {
        val f = com.ps4smartcheat.core.cheat.CodeCheats.asGoldHenFile(p) ?: run { _error.value = "This profile has no code patches"; return }
        _gh.value = f
        _ghLibrary.value = true
        ghProfileId = p.id
        _ghChecks.value = emptyMap()
        _error.value = null
        checkGoldHen()
    }

    private val _report = MutableStateFlow<Pair<CheatProfile, com.ps4smartcheat.core.validate.ValidationReport>?>(null)
    /** Result of the cross-session check (profile + the game's address list). */
    val report = _report.asStateFlow()

    /** Read-only check of what still holds in this game session (after a restart or update). */
    fun checkSession(p: CheatProfile) {
        val sel = c.selectedProcess.value?.takeIf { !it.exited } ?: run { _error.value = "Select the running game first"; return }
        viewModelScope.launch {
            try {
                val regions = c.backend.getMemoryRegions(sel.pid)
                c.updateRegions(regions)
                val addresses = c.addresses.entries(c.gameKey(sel))
                _report.value = p to com.ps4smartcheat.core.validate.SessionValidator.validate(c.backend, sel.pid, regions, addresses, p, sel.name)
                _error.value = null
            } catch (e: BackendException) {
                _error.value = com.ps4smartcheat.core.diag.ErrorAdvisor.diagnose(e).format()
            }
        }
    }

    fun closeReport() { _report.value = null }

    /** Reads the bytes of every applicable mod in the selected game (read-only). */
    fun checkGoldHen() {
        if (_ghLibrary.value && !reloadLibraryFile()) return
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
        if (_ghLibrary.value) {
            // The profile changed since the card was drawn: show the current mods instead of writing by an old index.
            val before = _gh.value?.fingerprint()
            if (!reloadLibraryFile()) return
            if (_gh.value?.fingerprint() != before) { _error.value = "The profile changed — the switches were refreshed. Check and try again."; checkGoldHen(); return }
        }
        val f = _gh.value ?: return
        val sel = c.selectedProcess.value ?: return
        viewModelScope.launch {
            try {
                val regions = c.backend.getMemoryRegions(sel.pid)
                val check = com.ps4smartcheat.core.cheat.GoldHenImport.check(c.backend, sel.pid, f, f.mods[index], regions)
                _ghChecks.value = _ghChecks.value + (index to check)
                if (if (enable) !check.canEnable else !check.canDisable) {
                    _error.value = "\"${f.mods[index].name}\" cannot be switched ${if (enable) "on" else "off"}: " +
                        check.parts.joinToString("; ") { "${it.label} is ${it.state.label}" } + " — nothing written."
                    return@launch
                }
                // ON in file order, OFF in reverse (an injection's jump goes last ON and first OFF); patches already in
                // the target state (an interrupted toggle) are skipped.
                val plans = check.writeOrder(enable).map { i ->
                    val p = check.mod.patches[i]
                    c.editor.planBytes(sel.pid, sel.name, check.addresses[i], if (enable) p.on else p.off)
                }
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
        c.cheats.updateProfile(
            p.copy(
                name = name.trim(),
                titleId = titleId.trim().ifBlank { null }?.let { GoldHenExporter.normalizeTitleId(it) ?: it },
                gameVersion = version.trim().ifBlank { null }?.let { c.normalizedVersion(it) },
            ),
        )
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

    private fun runningTitle(p: CheatProfile) = c.selectedProcess.value?.takeIf { !it.exited && it.name == p.processName }?.titleId

    /** Live check for the export dialog (normalised title ID/version, file name, what is exported). Read-only. */
    fun goldHenPreview(p: CheatProfile, titleId: String, version: String, gameName: String): GoldHenExporter.Preview =
        GoldHenExporter.preview(p, titleId, version, gameName, runningTitle(p))

    /**
     * Builds the GoldHEN JSON with the dialog's values and returns the file name for the save picker, or null with
     * the reason in [GoldHenExporter.Preview.problems] (shown in the dialog). Normalised title ID and version are
     * stored in the profile so the next export needs no correction.
     */
    fun prepareGoldHen(p: CheatProfile, titleId: String, version: String, gameName: String, credits: String): String? {
        val pre = goldHenPreview(p, titleId, version, gameName)
        if (!pre.ok) return null
        val fixed = p.copy(titleId = pre.titleId, gameVersion = (pre.version as GoldHenExporter.VersionCheck.Ok).version)
        val r = GoldHenExporter.export(fixed, gameName, credits.split(',').map { it.trim() }, runningTitleId = runningTitle(p))
        val json = r.json ?: run { _error.value = r.errors.joinToString("\n"); return null }
        if (fixed.titleId != p.titleId || fixed.gameVersion != p.gameVersion) {
            viewModelScope.launch {
                try { c.cheats.updateProfile(fixed) } catch (e: Exception) { c.log.warn("Could not store the corrected title ID/version: ${e.message}") }
            }
        }
        pendingGoldHen = json
        pendingGoldHenName = pre.fileName
        _message.value = "GoldHEN export: ${r.exported} mod(s) → ${pre.fileName}" + if (r.skipped.isNotEmpty()) "; skipped:\n" + r.skipped.joinToString("\n") else ""
        return pre.fileName
    }

    private var pendingGoldHenName: String? = null

    fun writeGoldHen(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        val text = pendingGoldHen ?: return@launch
        try {
            withContext(Dispatchers.IO) { resolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            pendingGoldHen = null
            pendingGoldHenName?.let { _message.value = "Saved $it. Copy it to ${GoldHenExporter.CHEATS_DIR} on the PS4 (keep the file name)." }
        } catch (e: Exception) {
            _error.value = "GoldHEN export failed: ${e.message}"
        }
    }

    fun clearMessages() { _message.value = null; _error.value = null }
}

@Composable
fun CheatsScreen() {
    val vm = appViewModel { CheatsViewModel(it) }
    // Coming back to this screen (e.g. after injecting or removing code elsewhere): never show an old check.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.onShown() }
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
    val ghLibrary by vm.ghLibrary.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
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
            item { GoldHenCard(f, ghChecks, ghMismatch, canWrite = selected?.exited == false, onCheck = vm::checkGoldHen, onClose = vm::closeGoldHen, onToggle = vm::prepareGoldHenToggle, library = ghLibrary) }
        }
        report?.let { (p, r) ->
            item { ValidationCard(p, r, onClose = vm::closeReport) }
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
                    OutlinedButton(
                        onClick = { goldHenProfile = if (goldHenProfile?.id == p.id) null else p },
                        enabled = p.cheats.isNotEmpty(), modifier = Modifier.testTag("ghExport-${p.name}"),
                    ) { Text("GoldHEN export") }
                }
                if (goldHenProfile?.id == p.id) {
                    HorizontalDivider()
                    GoldHenExportForm(
                        p, vm,
                        onExport = { fileName -> goldHenProfile = null; goldHenLauncher.launch(fileName) },
                        onDismiss = { goldHenProfile = null },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.cheats.any { it.isPatch }) {
                        OutlinedButton(onClick = { vm.openCodePatches(p) }, enabled = isCurrentGame, modifier = Modifier.testTag("codeSwitches-${p.name}")) { Text("ON/OFF switches") }
                    }
                    TextButton(onClick = { vm.checkSession(p) }, enabled = isCurrentGame, modifier = Modifier.testTag("checkSession-${p.name}")) { Text("Check after restart") }
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
}

/**
 * GoldHEN export form (inside the profile card): title ID / version corrected as typed, file name, PS4 folder and what
 * is exported shown before anything is written; problems are listed here and Export is enabled only when valid.
 */
@Composable
private fun GoldHenExportForm(p: CheatProfile, vm: CheatsViewModel, onExport: (String) -> Unit, onDismiss: () -> Unit) {
    var gameName by remember(p.id) { mutableStateOf(p.name) }
    var titleId by remember(p.id) { mutableStateOf(p.titleId.orEmpty()) }
    var version by remember(p.id) { mutableStateOf(p.gameVersion.orEmpty()) }
    var credits by remember(p.id) { mutableStateOf("") }
    val pre = vm.goldHenPreview(p, titleId, version, gameName)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("ghxForm")) {
        Text("Export GoldHEN cheat file", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(gameName, { gameName = it }, label = { Text("Game name (inside the file)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ghxName"))
        OutlinedTextField(titleId, { titleId = it }, label = { Text("Title ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ghxTitle"))
        val t = pre.titleId
        Text(
            if (t == null) "e.g. CUSA05294" else if (t != titleId) "Exported as $t" else "Title ID OK",
            style = MaterialTheme.typography.bodySmall, color = if (t == null && titleId.isNotBlank()) MaterialTheme.colorScheme.error else dim,
        )
        OutlinedTextField(version, { version = it }, label = { Text("Game version") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ghxVersion"))
        when (val v = pre.version) {
            is GoldHenExporter.VersionCheck.Ok ->
                Text(if (v.changed) "Exported as ${v.version}" else "Version OK", style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.testTag("ghxVersionNote"))
            is GoldHenExporter.VersionCheck.Invalid -> {
                Text(
                    if (version.isBlank()) "e.g. 01.25" else v.reason, style = MaterialTheme.typography.bodySmall,
                    color = if (version.isBlank()) dim else MaterialTheme.colorScheme.error,
                )
                if (v.suggestions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Did you mean", style = MaterialTheme.typography.bodySmall)
                        v.suggestions.forEach { s -> OutlinedButton(onClick = { version = s }, modifier = Modifier.testTag("ghxSuggest-$s")) { Text(s) } }
                    }
                }
            }
        }
        Text("Process: ${p.processName}", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(credits, { credits = it }, label = { Text("Credits (comma separated, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("File", style = MaterialTheme.typography.labelLarge)
        Text(pre.fileName ?: "—", style = mono, modifier = Modifier.testTag("ghxFileName"))
        Text(
            if (p.processName == GoldHenExporter.MAIN_PROCESS) "Named {titleid}_{version}.json, as GoldHEN expects for eboot.bin."
            else "Named {titleid}_{version}_{process}.json: GoldHEN needs the process (${p.processName}) in the name for games that do not run eboot.bin.",
            style = MaterialTheme.typography.bodySmall, color = dim,
        )
        pre.uploadPath?.let { Text("Copy to $it (e.g. by FTP); keep the file name.", style = MaterialTheme.typography.bodySmall, color = dim) }
        Text("${pre.exportable} mod(s) will be exported", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ghxCount"))
        if (pre.skipped.isNotEmpty()) {
            Text("Not exported (${pre.skipped.size}):", style = MaterialTheme.typography.bodySmall)
            pre.skipped.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = dim) }
            if (pre.skipped.size > 5) Text("• … ${pre.skipped.size - 5} more", style = MaterialTheme.typography.bodySmall, color = dim)
        }
        pre.problems.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(
            "Offsets are relative to the game executable (as PS4CheaterNeo reads GoldHEN files); not yet verified on hardware. " +
                "Corrected title ID and version are saved in the profile.",
            style = MaterialTheme.typography.bodySmall, color = dim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.prepareGoldHen(p, titleId, version, gameName, credits)?.let(onExport) },
                enabled = pre.ok, modifier = Modifier.testTag("ghxExport"),
            ) { Text("Export…") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

@Composable
private fun CheatRow(entry: CheatEntry, check: CheatCheck?, canApply: Boolean, onApply: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            val where = entry.module?.let { "${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}" } ?: Address.format(entry.address)
            if (entry.isPatch) {
                Text("$where · code patch${entry.group?.let { " · group $it" } ?: ""}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                entry.assembly?.let { Text(it.replace('\n', ';'), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("ON ${entry.patchBytes} / OFF ${entry.originalBytes}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("$where · ${entry.type.label} → ${entry.replacementValue ?: "—"}" + (entry.group?.let { " · group $it" } ?: ""), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (check != null) {
                Text(
                    check.status.label + (check.resolvedAddress?.let { " @ ${Address.format(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (check.status.usable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!entry.isPatch) TextButton(onClick = onApply, enabled = canApply) { Text(if (check == null) "Validate first" else "Apply") }
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
    library: Boolean = false,
) {
    SectionCard(if (library) "Code patches: ${f.name}" else "GoldHEN file: ${f.name.ifBlank { "unnamed" }}") {
        Text("${f.titleId.ifBlank { "no title ID" }} · v${f.version.ifBlank { "?" }} · ${f.process} · ${f.mods.size} mod(s)", style = MaterialTheme.typography.bodySmall)
        Text(
            if (library) "Each switch writes the saved ON or OFF bytes after confirmation and verifies them. It is only offered when the code holds exactly the other state, so another game version is refused."
            else "Offsets are read as relative to the start of ${f.process} (PS4CheaterNeo's interpretation; unverified on hardware). " +
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
                        ck.partial -> "${m.patches.size} patch(es) — partly on (${ck.states.count { it == com.ps4smartcheat.core.cheat.PatchState.ON }} of ${m.patches.size}, interrupted): Enable completes it, Disable undoes it"
                        else -> "${m.patches.size} patch(es) — " + (ck.state?.label ?: ck.states.joinToString { it.label })
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (m.applicable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary)
                }
                if (ck != null && ck.canEnable) Button(onClick = { onToggle(i, true) }, enabled = canWrite, modifier = Modifier.testTag("ghEnable-$i")) { Text("Enable") }
                if (ck != null && ck.canDisable) OutlinedButton(onClick = { onToggle(i, false) }, enabled = canWrite, modifier = Modifier.testTag("ghDisable-$i")) { Text("Disable") }
            }
            // Every part on its own: where it was looked for, its state and — when it does not match — the bytes.
            if (ck != null && (ck.parts.size > 1 || ck.parts.any { it.reason != null })) PartList(ck, i)
        }
    }
}


/** The parts of one mod (e.g. cave + jump of an injection): location, state, and expected/actual bytes on a mismatch. */
@Composable
private fun PartList(ck: com.ps4smartcheat.core.cheat.ModCheck, modIndex: Int) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    fun hex(b: ByteArray?) = b?.let { com.ps4smartcheat.core.util.Hex.encode(it, " ") } ?: "—"
    Column(Modifier.fillMaxWidth().padding(start = 8.dp).testTag("ghParts-$modIndex"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ck.parts.forEach { part ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tone = when (part.state) {
                    com.ps4smartcheat.core.cheat.PatchState.ON -> com.ps4smartcheat.app.ui.components.Tone.GOOD
                    com.ps4smartcheat.core.cheat.PatchState.OFF -> com.ps4smartcheat.app.ui.components.Tone.NEUTRAL
                    else -> com.ps4smartcheat.app.ui.components.Tone.BAD
                }
                com.ps4smartcheat.app.ui.components.StatusPill(
                    when (part.state) {
                        com.ps4smartcheat.core.cheat.PatchState.ON -> "ON"
                        com.ps4smartcheat.core.cheat.PatchState.OFF -> "OFF"
                        com.ps4smartcheat.core.cheat.PatchState.MISMATCH -> "MISMATCH"
                        com.ps4smartcheat.core.cheat.PatchState.UNMAPPED -> "UNMAPPED"
                    },
                    tone, modifier = Modifier.testTag("ghPart-$modIndex-${part.index}"),
                )
                Text(part.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Text(
                (if (part.address == -1L) "" else com.ps4smartcheat.core.util.Address.format(part.address) + " = ") + part.location,
                style = mono, color = dim,
            )
            part.reason?.let { why ->
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("expected OFF  ${hex(part.off)}", style = mono)
                Text("expected ON   ${hex(part.on)}", style = mono)
                Text("in memory     ${hex(part.actual)}", style = mono, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ghActual-$modIndex-${part.index}"))
            }
        }
    }
}

@Composable
private fun ValidationCard(p: CheatProfile, r: com.ps4smartcheat.core.validate.ValidationReport, onClose: () -> Unit) {
    SectionCard("Session check: ${p.name}") {
        Text("pid ${r.pid}: ${r.valid} OK · ${r.invalid} need attention. Read-only — nothing was changed.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sessionSummary"))
        r.items.forEach { i ->
            HorizontalDivider()
            Text("${i.name} (${i.kind.label})", style = MaterialTheme.typography.bodyMedium)
            Text(
                i.validity.label + " — " + i.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (i.validity.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onClose) { Text("Close") }
    }
}
