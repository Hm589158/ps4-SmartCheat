package com.ps4smartcheat.app.data

import android.content.Context
import com.ps4smartcheat.core.backend.ConnectionMonitor
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.logging.EventLevel
import com.ps4smartcheat.core.process.GameDetector
import com.ps4smartcheat.core.process.ProcessDetails
import com.ps4smartcheat.core.process.ProcessEvent
import com.ps4smartcheat.core.process.ProcessKind
import com.ps4smartcheat.core.process.ProcessWatcher
import com.ps4smartcheat.core.process.listProcessDetails
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.cheat.CheatRepository
import com.ps4smartcheat.core.cheat.CheatEntry
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.util.Hex
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.watch.WatchEntry
import com.ps4smartcheat.core.watch.WatchKeys
import com.ps4smartcheat.core.pointer.PointerChain
import com.ps4smartcheat.core.watch.WatchRepository
import com.ps4smartcheat.core.edit.MemoryEditor
import com.ps4smartcheat.core.live.FreezeManager
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.ProcessEntry
import com.ps4smartcheat.core.model.ProcessInfo
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.FileSnapshotStorage
import com.ps4smartcheat.core.scan.InMemorySnapshotStorage
import com.ps4smartcheat.core.scan.ScanController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Process the user picked on the Processes screen. */
data class SelectedProcess(
    val entry: ProcessEntry,
    val info: ProcessInfo?,
    val regions: List<MemoryRegion>,
    val kind: ProcessKind = ProcessKind.OTHER,
    /** Set when the process is gone; its scan session and journal were discarded. */
    val exitedReason: String? = null,
) {
    val exited: Boolean get() = exitedReason != null
    val pid: Int get() = entry.pid
    val name: String get() = entry.name
    val titleId: String? get() = info?.titleId?.ifBlank { null }
}

/** Manual dependency container, created once per process by [com.ps4smartcheat.app.SmartCheatApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = AppLog()
    val settings = SettingsStore(context)
    private val client = Ps4DebugClient(events = log)
    val backend: DebugBackend = client

    private val cacheDir: File = context.cacheDir
    val scanner = ScanController(
        backend = backend,
        scope = appScope,
        storageFactory = { size: Long ->
            if (size <= IN_MEMORY_SNAPSHOT_LIMIT) InMemorySnapshotStorage(size)
            else FileSnapshotStorage(File.createTempFile("snapshot-", ".bin", cacheDir))
        },
    )
    val editor = MemoryEditor(backend, { settings.value.editPolicy() })
    val freezer = FreezeManager(backend, appScope, { settings.value.editPolicy() })
    val cheats = CheatRepository(File(context.filesDir, "cheats.json"))
    /** Per-game saved address lists. Only read automatically; never written to the console by themselves. */
    val addresses = WatchRepository(File(context.filesDir, "addresses.json"))

    private val _selected = MutableStateFlow<SelectedProcess?>(null)
    val selectedProcess: StateFlow<SelectedProcess?> = _selected.asStateFlow()

    val monitor = ConnectionMonitor(backend, appScope)
    val watcher = ProcessWatcher(backend, appScope, events = log)

    init {
        // Remove stale snapshot files from a previous run.
        cacheDir.listFiles { f -> f.name.startsWith("snapshot-") }?.forEach { it.delete() }
        appScope.launch {
            cheats.load()
            cheats.loadError?.let { log.error(it) }
        }
        appScope.launch {
            addresses.load()
            addresses.loadError?.let { log.error(it) }
        }
        appScope.launch {
            var last: ConnectionState = ConnectionState.Disconnected
            backend.state.collect { st ->
                if (st != last) {
                    when (st) {
                        is ConnectionState.Connected -> {
                            monitor.start()
                            // First connect (not a reconnect): pick the running game automatically.
                            if (last !is ConnectionState.Reconnecting && settings.value.autoSelectGame && _selected.value == null) {
                                appScope.launch { autoSelectGame() }
                            }
                        }
                        is ConnectionState.Failed -> {
                            log.error("Connection lost: ${st.message}", category = EventCategory.CONNECTION)
                            freezer.stopAll("connection lost")
                            monitor.stop()
                        }
                        ConnectionState.Disconnected -> {
                            freezer.stopAll("disconnected")
                            monitor.stop()
                        }
                        is ConnectionState.Reconnecting -> {
                            // Never keep re-applying writes across a reconnect.
                            freezer.stopAll("connection interrupted")
                        }
                        is ConnectionState.Connecting -> {}
                    }
                    last = st
                }
            }
        }
    }

    init {
        appScope.launch {
            watcher.processEvents.collect { ev ->
                when (ev) {
                    is ProcessEvent.Exited -> onProcessExited(ev.identity.pid, ev.identity.name)
                    is ProcessEvent.Restarted -> onProcessRestarted(ev.identity.pid, ev.replacement)
                }
            }
        }
        appScope.launch {
            scanner.lastStats.collect { st ->
                if (st != null) log.event(
                    EventLevel.INFO, EventCategory.SCAN, "scan step finished",
                    "backend" to st.backend.name, "mode" to st.mode.name, "ms" to st.durationMs, "results" to st.results,
                    "serverTotal" to st.serverTotal, "netBytes" to st.traffic.total, "fallbacks" to st.fallbacks.joinToString(" | "),
                )
            }
        }
    }

    fun select(process: SelectedProcess?) {
        _selected.value = process
        scanner.activate(process?.pid)
        watcher.watch(process?.takeIf { !it.exited }?.let { ProcessDetails(it.entry, it.info, it.kind, "").identity })
        process?.let { log.event(EventLevel.INFO, EventCategory.PROCESS, "process selected", "pid" to it.pid, "name" to it.name, "title" to it.titleId, "kind" to it.kind.name) }
    }

    /** Loads info + memory map and selects [details]. */
    suspend fun selectDetails(details: ProcessDetails) {
        val regions = backend.getMemoryRegions(details.pid)
        select(SelectedProcess(details.entry, details.info, regions, details.kind))
    }

    /** Selects the running game when exactly one is detected. Read-only: nothing is written. */
    suspend fun autoSelectGame(): ProcessDetails? = try {
        GameDetector.pickGame(backend.listProcessDetails())?.also {
            selectDetails(it)
            log.notify("Detected game ${it.titleId ?: it.name} (pid ${it.pid}) — selected", EventCategory.PROCESS)
        }
    } catch (e: BackendException) {
        log.warn("Game detection failed: ${e.message}", EventCategory.PROCESS)
        null
    }

    private fun onProcessExited(pid: Int, name: String) {
        scanner.invalidate(pid, "$name (pid $pid) exited")
        freezer.frozen.value.filter { it.pid == pid }.forEach { freezer.unfreeze(it.id) }
        editor.forgetProcess(pid)
        _selected.value = _selected.value?.takeIf { it.pid == pid }?.copy(exitedReason = "$name (pid $pid) exited") ?: _selected.value
        log.notify("$name (pid $pid) exited — its scan session was closed", EventCategory.PROCESS)
    }

    private fun onProcessRestarted(oldPid: Int, replacement: ProcessDetails) {
        if (!settings.value.autoSelectGame) return
        appScope.launch {
            try {
                selectDetails(replacement)
                log.notify("${replacement.titleId ?: replacement.name} restarted as pid ${replacement.pid} (was $oldPid) — selected; start a new scan", EventCategory.PROCESS)
            } catch (e: BackendException) {
                log.warn("Could not select restarted process: ${e.message}", EventCategory.PROCESS)
            }
        }
    }

    /**
     * Adds [address] of the selected process to its game's address list (module-relative when it lies in a
     * module image, so it survives restarts). Returns a user-facing message.
     */
    suspend fun addToAddressList(address: Long, type: ValueType, label: String? = null): String {
        val sel = _selected.value ?: return "Select a process first"
        if (sel.exited) return "${sel.name} is no longer running"
        val module = ModuleResolver.refFor(address, sel.regions)
        val name = label?.trim()?.ifEmpty { null }
            ?: module?.let { "${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}" }
            ?: Address.format(address)
        val res = addresses.add(sel.titleId, sel.name, WatchEntry("", name, type, address, module, sel.pid))
        log.event(EventLevel.INFO, EventCategory.CHEAT, "address list add", "address" to Address.format(address), "type" to type.name, "duplicate" to res.alreadyPresent)
        return if (res.alreadyPresent) "Already in the address list as \"${res.entry.label}\""
        else "Added \"${res.entry.label}\" to the address list" + if (module == null) " (absolute address: valid for this session)" else ""
    }

    /**
     * Saves [address] of the selected process as a cheat. [profileId] null → a new profile named [newProfileName]
     * for [gameVersion]. The module-relative form is stored when the address lies in a module image.
     * Returns a user-facing message; throws [IllegalArgumentException] for invalid input.
     */
    suspend fun saveCheat(
        address: Long, type: ValueType, name: String, replacement: String?, original: ByteArray?,
        profileId: String?, newProfileName: String, gameVersion: String,
    ): String {
        val sel = _selected.value ?: throw IllegalArgumentException("Select a process first")
        require(name.isNotBlank()) { "Enter a cheat name" }
        val value = replacement?.trim()?.ifBlank { null }
        if (value != null) ValueCodec.parse(type, value) // throws ValueParseException (an IllegalArgumentException)
        val pid = profileId ?: cheats.createProfile(newProfileName.ifBlank { sel.titleId ?: sel.name }, sel.titleId, gameVersion, sel.name).id
        val module = ModuleResolver.refFor(address, sel.regions)
        val entry = CheatEntry(
            id = cheats.newId(), name = name.trim(), type = type, address = address, module = module, replacementValue = value,
            originalBytes = original?.takeIf { it.size == type.size }?.let { Hex.encode(it) }, discoveredPid = sel.pid,
        )
        cheats.upsertCheat(pid, entry)
        log.event(EventLevel.INFO, EventCategory.CHEAT, "cheat saved", "address" to Address.format(address), "module" to (module?.name ?: "absolute"))
        return "Saved \"${entry.name}\"" + if (module == null) " (absolute address — may not survive a game restart)" else " (${module.name} relative)"
    }

    fun gameKey(sel: SelectedProcess): String = WatchKeys.gameKey(sel.titleId, sel.name)

    /** Pointer scans run here (application scope) so they survive leaving the screen. */
    val pointerScan by lazy { PointerScanService(this, File(cacheDir, "pointer-map")) }

    /** Adds a pointer-chain entry for the selected game. [resolved] is the address the chain leads to now. */
    suspend fun addPointerToAddressList(chain: PointerChain, type: ValueType, resolved: Long): String {
        val sel = _selected.value ?: return "Select a process first"
        val res = addresses.add(sel.titleId, sel.name, WatchEntry("", chain.describe(), type, resolved, null, sel.pid, pointer = chain))
        log.event(EventLevel.INFO, EventCategory.CHEAT, "address list add pointer", "chain" to chain.describe(), "type" to type.name, "duplicate" to res.alreadyPresent)
        return if (res.alreadyPresent) "Already in the address list as \"${res.entry.label}\"" else "Added pointer ${chain.describe()} to the address list"
    }

    fun updateRegions(regions: List<MemoryRegion>) {
        _selected.value = _selected.value?.copy(regions = regions)
    }

    companion object {
        const val IN_MEMORY_SNAPSHOT_LIMIT = 64L * 1024 * 1024
    }
}
