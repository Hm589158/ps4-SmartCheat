package com.ps4smartcheat.app.data

import android.content.Context
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.cheat.CheatRepository
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
) {
    val pid: Int get() = entry.pid
    val name: String get() = entry.name
    val titleId: String? get() = info?.titleId?.ifBlank { null }
}

/** Manual dependency container, created once per process by [com.ps4smartcheat.app.SmartCheatApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = AppLog()
    val settings = SettingsStore(context)
    val backend: DebugBackend = Ps4DebugClient()

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

    private val _selected = MutableStateFlow<SelectedProcess?>(null)
    val selectedProcess: StateFlow<SelectedProcess?> = _selected.asStateFlow()

    init {
        // Remove stale snapshot files from a previous run.
        cacheDir.listFiles { f -> f.name.startsWith("snapshot-") }?.forEach { it.delete() }
        appScope.launch {
            cheats.load()
            cheats.loadError?.let { log.error(it) }
        }
        appScope.launch {
            var last: ConnectionState = ConnectionState.Disconnected
            backend.state.collect { st ->
                if (st != last) {
                    when (st) {
                        is ConnectionState.Connected -> log.info("Connected to ${st.info.host}:${st.info.port} (ps4debug ${st.info.debuggerVersion})")
                        is ConnectionState.Failed -> {
                            log.error("Connection lost: ${st.message}")
                            freezer.stopAll("connection lost")
                        }
                        ConnectionState.Disconnected -> freezer.stopAll("disconnected")
                        is ConnectionState.Connecting -> {}
                    }
                    last = st
                }
            }
        }
    }

    fun select(process: SelectedProcess?) {
        _selected.value = process
        scanner.activate(process?.pid)
    }

    fun updateRegions(regions: List<MemoryRegion>) {
        _selected.value = _selected.value?.copy(regions = regions)
    }

    companion object {
        const val IN_MEMORY_SNAPSHOT_LIMIT = 64L * 1024 * 1024
    }
}
