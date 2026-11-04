package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.model.MemoryRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ScanStatus {
    data object Idle : ScanStatus
    data class Running(val progress: ScanProgress) : ScanStatus
    data class Completed(val message: String) : ScanStatus
    data class Failed(val message: String) : ScanStatus
    data object Cancelled : ScanStatus
}

/** Read-only summary of a per-process scan session. */
data class ScanSessionView(
    val pid: Int,
    val processName: String,
    val type: ValueType,
    val step: Int,
    val resultCount: Int,
    val truncated: Boolean,
    /** Non-null while an unknown-initial-value snapshot is pending its first comparison. */
    val snapshotCandidates: Long?,
    val lastMode: ScanMode?,
)

data class ResultRow(
    val index: Int,
    val address: Long,
    val type: ValueType,
    val previous: ByteArray,
    /** Fresh value read from the console, or null if it could not be read. */
    val current: ByteArray?,
) {
    val changed: Boolean get() = current != null && !current.contentEquals(previous)
}

private class Session(
    val pid: Int,
    val processName: String,
    val type: ValueType,
    val options: ScanOptions,
    val filter: RegionFilter,
) {
    var step = 0
    var results: ScanResults? = null
    var snapshot: MemorySnapshot? = null
    var lastMode: ScanMode? = null

    fun view() = ScanSessionView(
        pid, processName, type, step, results?.count ?: 0, results?.truncated ?: false,
        snapshot?.candidateCount, lastMode,
    )

    fun dispose() {
        snapshot?.close(); snapshot = null; results = null
    }
}

/**
 * Owns scan sessions (one per process id) and runs scans in [scope] so they survive UI navigation.
 * Results of a step only replace the previous ones when the step completes successfully; a cancelled
 * or failed step leaves the previous results untouched.
 */
class ScanController(
    private val backend: DebugBackend,
    private val scope: CoroutineScope,
    private val storageFactory: (Long) -> SnapshotStorage = { InMemorySnapshotStorage(it) },
) {
    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _session = MutableStateFlow<ScanSessionView?>(null)
    /** Session of the currently active process. */
    val session: StateFlow<ScanSessionView?> = _session.asStateFlow()

    private val sessions = HashMap<Int, Session>()
    private val lock = Mutex()
    private var activePid: Int? = null
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Switches the active process; existing sessions for other processes are kept. */
    fun activate(pid: Int?) {
        activePid = pid
        _session.value = pid?.let { sessions[it]?.view() }
    }

    fun firstScan(
        pid: Int,
        processName: String,
        type: ValueType,
        mode: ScanMode,
        valueText: String?,
        filter: RegionFilter = RegionFilter(),
        options: ScanOptions = ScanOptions(),
    ): Job {
        require(mode in ScanMode.FIRST_SCAN_MODES) { "${mode.label} cannot be used for a first scan" }
        val parsed = if (mode.needsValue) ValueCodec.parse(type, valueText.orEmpty()) else null
        val matcher = Matcher(type, mode, parsed)
        return launchStep {
            val regions = filter.select(backend.getMemoryRegions(pid))
            if (regions.isEmpty()) throw IllegalStateException("No memory regions match the region filter for pid $pid")
            val session = Session(pid, processName, type, options, filter)
            if (mode == ScanMode.UNKNOWN_INITIAL) {
                session.snapshot = ScanEngine.snapshot(backend, pid, regions, type, options, storageFactory, ::report)
            } else {
                session.results = ScanEngine.firstScan(backend, pid, regions, matcher, options, ::report)
            }
            session.step = 1
            session.lastMode = mode
            lock.withLock {
                sessions.remove(pid)?.dispose()
                sessions[pid] = session
            }
            activate(pid)
            summary(session)
        }
    }

    fun nextScan(mode: ScanMode, valueText: String?): Job {
        require(mode in ScanMode.NEXT_SCAN_MODES) { "${mode.label} cannot be used for a next scan" }
        val pid = activePid ?: throw IllegalStateException("No process selected")
        val session = sessions[pid] ?: throw IllegalStateException("Run a first scan before a next scan")
        val parsed = if (mode.needsValue) ValueCodec.parse(session.type, valueText.orEmpty()) else null
        val matcher = Matcher(session.type, mode, parsed)
        return launchStep {
            val current = backend.getMemoryRegions(pid)
            val snap = session.snapshot
            val newResults = if (snap != null) {
                ScanEngine.compareSnapshot(backend, pid, snap, current, matcher, session.options, ::report)
            } else {
                val prev = session.results ?: throw IllegalStateException("No previous results")
                ScanEngine.nextScan(backend, pid, prev, current, matcher, session.options, ::report)
            }
            lock.withLock {
                session.snapshot?.close()
                session.snapshot = null
                session.results = newResults
                session.step++
                session.lastMode = mode
            }
            if (activePid == pid) _session.value = session.view()
            summary(session)
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset(pid: Int) {
        if (isRunning) cancel()
        sessions.remove(pid)?.dispose()
        if (activePid == pid) _session.value = null
        _status.value = ScanStatus.Idle
    }

    /** Drops every session (e.g. after disconnect: absolute addresses are meaningless in a new session). */
    fun resetAll() {
        cancel()
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        _session.value = null
        _status.value = ScanStatus.Idle
    }

    fun results(pid: Int = activePid ?: -1): ScanResults? = sessions[pid]?.results

    /** Reads a page of results including fresh current values (batched reads). */
    suspend fun readPage(pageIndex: Int, pageSize: Int): List<ResultRow> {
        val pid = activePid ?: return emptyList()
        val res = sessions[pid]?.results ?: return emptyList()
        val range = res.page(pageIndex, pageSize)
        if (range.isEmpty()) return emptyList()
        val size = res.type.size
        val rows = ArrayList<ResultRow>(range.count())
        var i = range.first
        while (i <= range.last) {
            var j = i + 1
            while (j <= range.last && res.address(j) + size - res.address(i) <= PAGE_SPAN && res.address(j) >= res.address(i)) j++
            val start = res.address(i)
            val len = (res.address(j - 1) + size - start).toInt()
            val buf = try {
                backend.readMemory(pid, start, len)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            for (k in i until j) {
                val off = (res.address(k) - start).toInt()
                rows += ResultRow(k, res.address(k), res.type, res.valueBytes(k), buf?.copyOfRange(off, off + size))
            }
            i = j
        }
        return rows
    }

    private fun launchStep(block: suspend () -> String): Job {
        if (isRunning) throw IllegalStateException("A scan is already running")
        _status.value = ScanStatus.Running(ScanProgress(0, 0, 0, "Starting"))
        val j = scope.launch {
            try {
                _status.value = ScanStatus.Completed(block())
            } catch (e: CancellationException) {
                _status.value = ScanStatus.Cancelled
                throw e
            } catch (e: ScanTooLargeException) {
                _status.value = ScanStatus.Failed(e.message ?: "Scan too large")
            } catch (e: Exception) {
                _status.value = ScanStatus.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        job = j
        return j
    }

    private fun report(p: ScanProgress) {
        _status.value = ScanStatus.Running(p)
    }

    private fun summary(s: Session): String {
        s.snapshot?.let { return "Snapshot of ${it.totalBytes / 1024} KiB taken (${it.candidateCount} candidates). Change the value in game, then run a next scan." }
        val r = s.results ?: return "Done"
        return buildString {
            append("${r.count} result(s) after scan ${s.step}")
            if (r.truncated) append(" — result cap reached, list is incomplete; refine with a more specific value")
        }
    }

    companion object {
        const val PAGE_SPAN = 4096
    }
}

/** Convenience used by the editor/UI to describe where an address lives. */
fun List<MemoryRegion>.describe(address: Long): String =
    firstOrNull { it.contains(address) }?.let { r ->
        val n = r.name.ifBlank { "anon" }
        "$n+0x" + java.lang.Long.toHexString(address - r.start).uppercase() + " [" + r.protString() + "]"
    } ?: "unmapped"
