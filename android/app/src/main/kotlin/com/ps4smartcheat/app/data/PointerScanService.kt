package com.ps4smartcheat.app.data

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.logging.EventLevel
import com.ps4smartcheat.core.pointer.PointerChain
import com.ps4smartcheat.core.pointer.PointerScanOptions
import com.ps4smartcheat.core.pointer.PointerScanner
import com.ps4smartcheat.core.scan.ScanProgress
import com.ps4smartcheat.core.scan.ScanTooLargeException
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PointerScanState(
    val running: Boolean = false,
    val stage: String? = null,
    val progress: ScanProgress? = null,
    /** Game the chains belong to (title ID or process name). Chains are kept in memory across restarts of it. */
    val gameKey: String? = null,
    val target: Long? = null,
    val type: ValueType = ValueType.INT32,
    val chains: List<PointerChain> = emptyList(),
    val pointerCount: Long = 0,
    val notes: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Runs pointer scans in the application scope so they survive leaving the screen. Read-only: only
 * CMD_PROC_READ of mapped, non-executable regions is used. Results stay in memory until the next scan.
 */
class PointerScanService(private val c: AppContainer, private val spillDir: java.io.File) {
    init {
        // Run files from a scan that was interrupted by the app being killed.
        spillDir.mkdirs()
        spillDir.listFiles { f -> f.name.endsWith(".run") }?.forEach { it.delete() }
    }

    /** Bytes the pointer map would read for the selected process with the current settings (no console access). */
    fun estimateBytes(): Long? {
        val sel = c.selectedProcess.value ?: return null
        return PointerScanner.estimateBytes(sel.regions, c.settings.value.regionFilter().maxRegionBytes)
    }

    private val _state = MutableStateFlow(PointerScanState())
    val state: StateFlow<PointerScanState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(target: Long, type: ValueType, maxDepth: Int, maxOffset: Int) {
        val sel = c.selectedProcess.value ?: run { _state.update { it.copy(error = "Select a process first") }; return }
        if (sel.exited) { _state.update { it.copy(error = "${sel.name} is no longer running") }; return }
        if (job?.isActive == true) return
        val s = c.settings.value
        // The map spills to sorted run files in the cache, so it can hold far more pointers than fit in RAM.
        val options = PointerScanOptions(
            maxDepth = maxDepth, maxOffset = maxOffset, maxScanBytes = maxOf(s.scanOptions().maxSnapshotBytes, MAX_SCAN_BYTES),
            maxPointers = MAX_POINTERS, runSize = RUN_SIZE,
        )
        _state.value = PointerScanState(running = true, stage = "Reading memory map", gameKey = c.gameKey(sel), target = target, type = type)
        job = c.appScope.launch {
            try {
                val regions = c.backend.getMemoryRegions(sel.pid)
                val maxRegion = s.regionFilter().maxRegionBytes
                val mapRegions = PointerScanner.mapRegions(regions).filter { maxRegion <= 0 || it.size <= maxRegion }
                val map = PointerScanner.buildMap(c.backend, sel.pid, mapRegions, regions, options, spillDir) { p -> _state.update { it.copy(stage = "Building pointer map", progress = p) } }
                val res = map.use { PointerScanner.findChains(it, target, regions, options) { p -> _state.update { st -> st.copy(stage = "Searching chains", progress = p) } } }
                val notes = buildList {
                    if (res.mapTruncated) add("Pointer map capped at ${options.maxPointers} pointers: some chains may be missing.")
                    if (res.resultsTruncated) add("Stopped at ${options.maxResults} chains; restart the game and filter to narrow them down.")
                    if (maxRegion > 0) add("Regions larger than ${maxRegion / (1024 * 1024)} MiB were skipped (Settings).")
                }
                _state.update { it.copy(running = false, stage = null, progress = null, chains = res.chains, pointerCount = res.pointerCount, notes = notes) }
                c.log.event(
                    EventLevel.INFO, EventCategory.SCAN, "pointer scan finished", "target" to Address.format(target),
                    "pointers" to res.pointerCount, "chains" to res.chains.size, "bytes" to res.bytesScanned,
                )
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false, stage = null, progress = null, error = "Cancelled") }
                throw e
            } catch (e: ScanTooLargeException) {
                _state.update { it.copy(running = false, stage = null, progress = null, error = e.message) }
            } catch (e: BackendException) {
                _state.update { it.copy(running = false, stage = null, progress = null, error = "Pointer scan failed: ${e.message}") }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(running = false, stage = null, progress = null, error = "Not enough phone memory for the pointer map. Lower \"Max region size\" in Settings.") }
            }
        }
    }

    /** Keeps only the chains that lead to [newTarget] in the currently selected process (run after a restart). */
    fun filter(newTarget: Long) {
        val sel = c.selectedProcess.value ?: return
        val st = _state.value
        if (st.chains.isEmpty() || job?.isActive == true) return
        if (st.gameKey != c.gameKey(sel)) { _state.update { it.copy(error = "These chains belong to another game") }; return }
        _state.update { it.copy(running = true, stage = "Checking chains", error = null) }
        job = c.appScope.launch {
            try {
                val regions = c.backend.getMemoryRegions(sel.pid)
                val kept = PointerScanner.filter(c.backend, sel.pid, st.chains, regions, newTarget) { p -> _state.update { it.copy(progress = p) } }
                _state.update {
                    it.copy(
                        running = false, stage = null, progress = null, chains = kept, target = newTarget,
                        notes = listOf("${kept.size} of ${st.chains.size} chains lead to ${Address.format(newTarget)} in pid ${sel.pid}."),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false, stage = null, progress = null, error = "Cancelled") }
                throw e
            } catch (e: BackendException) {
                _state.update { it.copy(running = false, stage = null, progress = null, error = "Filter failed: ${e.message}") }
            }
        }
    }

    fun cancel() { job?.cancel() }

    companion object {
        /** 32 M pointers ≈ 512 MiB of run files at most; RAM while building ≈ 16 B × [RUN_SIZE] = 32 MiB. */
        const val MAX_POINTERS = 32_000_000
        const val RUN_SIZE = 2_000_000
        /** Memory read for one map; pointers are spilled, so this only bounds transfer time (≈ minutes over Wi-Fi). */
        const val MAX_SCAN_BYTES = 2L * 1024 * 1024 * 1024
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
