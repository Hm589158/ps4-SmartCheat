package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.CommandFailedException
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.backend.Traffic
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.protocol.ServerScanTransport
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
    /** Backend that produced the current results. */
    val backend: ScanBackendKind? = null,
    /** Survivors held on the PS4 (Turbo); can exceed [resultCount] when the local list is capped. */
    val serverTotal: Long? = null,
)

/** Measured figures for one completed scan step. */
data class ScanStats(
    val backend: ScanBackendKind,
    val mode: ScanMode,
    val durationMs: Long,
    /** Memory bytes covered (first scans) — candidates are counted for refines. */
    val scannedBytes: Long,
    val candidates: Long,
    val traffic: Traffic,
    val results: Int,
    val serverTotal: Long?,
    /** Why faster backends were not used for this step. */
    val fallbacks: List<String>,
) {
    val mibPerSecond: Double? get() = if (durationMs <= 0 || scannedBytes <= 0) null else scannedBytes / 1048576.0 / (durationMs / 1000.0)
}

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
    var backend: ScanBackendKind? = null
    /** Connection epoch of the Turbo server-resident session backing [results], if any. */
    var turboEpoch: Long? = null

    fun view() = ScanSessionView(
        pid, processName, type, step, results?.count ?: 0, results?.truncated ?: false,
        snapshot?.candidateCount, lastMode, backend, results?.serverTotal,
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
    private val transport: ServerScanTransport? = backend as? ServerScanTransport,
) {
    private val server = transport?.let { ServerScanner(it) }
    /** Session that owns the (single, per-connection) Turbo resident set on the PS4. */
    private var turboOwner: Session? = null

    private val _lastStats = MutableStateFlow<ScanStats?>(null)
    val lastStats: StateFlow<ScanStats?> = _lastStats.asStateFlow()

    @Volatile private var stepStart = 0L
    @Volatile private var stepTraffic = Traffic(0, 0)
    @Volatile private var currentBackend: ScanBackendKind? = null

    /** Backends usable for a request right now (for display); CLIENT is always available. */
    fun availableBackends(): List<ScanBackendKind> {
        val caps = transport?.capabilities ?: return listOf(ScanBackendKind.CLIENT)
        return buildList {
            if (caps.turboResident) add(ScanBackendKind.TURBO)
            if (caps.serverScan) add(ScanBackendKind.SERVER)
            add(ScanBackendKind.CLIENT)
        }
    }
    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _session = MutableStateFlow<ScanSessionView?>(null)
    /** Session of the currently active process. */
    val session: StateFlow<ScanSessionView?> = _session.asStateFlow()

    private val sessions = HashMap<Int, Session>()
    private val lock = Mutex()
    private var activePid: Int? = null
    private var job: Job? = null

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True from the moment a step is started until just before its final status is published. */
    val isRunning: Boolean get() = running.get()

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
        return launchStep(mode) {
            val regions = filter.select(backend.getMemoryRegions(pid))
            if (regions.isEmpty()) throw IllegalStateException("No memory regions match the region filter for pid $pid")
            val session = Session(pid, processName, type, options, filter)
            val fallbacks = ArrayList<String>()
            val total = regions.sumOf { it.size }
            if (mode == ScanMode.UNKNOWN_INITIAL) {
                currentBackend = ScanBackendKind.CLIENT
                session.snapshot = ScanEngine.snapshot(backend, pid, regions, type, options, storageFactory, ::report)
                session.backend = ScanBackendKind.CLIENT
                fallbacks += "unknown-initial-value snapshots are kept on Android"
            } else {
                runFirst(session, pid, regions, matcher, options, fallbacks)
            }
            session.step = 1
            session.lastMode = mode
            lock.withLock {
                sessions.remove(pid)?.let { old -> if (old !== session) { releaseTurbo(old); old.dispose() } }
                sessions[pid] = session
            }
            activate(pid)
            finishStats(session, mode, total, 0, fallbacks)
            summary(session)
        }
    }

    private suspend fun runFirst(
        session: Session, pid: Int, regions: List<MemoryRegion>, matcher: Matcher, options: ScanOptions, fallbacks: MutableList<String>,
    ) {
        for (kind in order(options.backend)) {
            val reason = ineligible(kind, matcher, options, first = true)
            if (reason != null) { if (kind != ScanBackendKind.CLIENT) fallbacks += "${kind.label}: $reason"; continue }
            currentBackend = kind
            try {
                when (kind) {
                    ScanBackendKind.TURBO -> {
                        val step = server!!.turboFirst(pid, regions, matcher, options, ::report)
                        session.results = step.results
                        session.turboEpoch = step.epoch
                        turboOwner = session
                    }
                    ScanBackendKind.SERVER -> session.results = server!!.iterativeFirst(pid, regions, matcher, options, ::report)
                    ScanBackendKind.CLIENT -> session.results = ScanEngine.firstScan(backend, pid, regions, matcher, options, ::report)
                }
                session.backend = kind
                return
            } catch (e: ServerScanUnavailableException) {
                fallbacks += "${kind.label}: ${e.message}"
            } catch (e: CommandFailedException) {
                fallbacks += "${kind.label}: ${e.message}"
            }
        }
        throw IllegalStateException("No scan backend could run this scan: ${fallbacks.joinToString("; ")}")
    }

    fun nextScan(mode: ScanMode, valueText: String?): Job {
        require(mode in ScanMode.NEXT_SCAN_MODES) { "${mode.label} cannot be used for a next scan" }
        val pid = activePid ?: throw IllegalStateException("No process selected")
        val session = sessions[pid] ?: throw IllegalStateException("Run a first scan before a next scan")
        val parsed = if (mode.needsValue) ValueCodec.parse(session.type, valueText.orEmpty()) else null
        val matcher = Matcher(session.type, mode, parsed)
        return launchStep(mode) {
            val current = backend.getMemoryRegions(pid)
            val snap = session.snapshot
            val fallbacks = ArrayList<String>()
            val candidates = session.results?.count?.toLong() ?: snap?.candidateCount ?: 0L
            var usedBackend = ScanBackendKind.CLIENT
            var turboEpoch: Long? = null
            val newResults = if (snap != null) {
                currentBackend = ScanBackendKind.CLIENT
                ScanEngine.compareSnapshot(backend, pid, snap, current, matcher, session.options, ::report)
            } else {
                val prev = session.results ?: throw IllegalStateException("No previous results")
                var out: ScanResults? = null
                for (kind in order(session.options.backend)) {
                    val reason = nextIneligible(kind, session, prev, matcher, current)
                    if (reason != null) { if (kind != ScanBackendKind.CLIENT) fallbacks += "${kind.label}: $reason"; continue }
                    currentBackend = kind
                    try {
                        out = when (kind) {
                            ScanBackendKind.TURBO -> server!!.turboNext(pid, matcher, session.options, ::report).also { turboEpoch = it.epoch }.results
                            ScanBackendKind.SERVER -> server!!.iterativeNext(pid, prev, current, matcher, session.options, ::report)
                            ScanBackendKind.CLIENT -> ScanEngine.nextScan(backend, pid, prev, current, matcher, tunedOptions(session, prev, current), ::report)
                        }
                        usedBackend = kind
                        break
                    } catch (e: ServerScanUnavailableException) {
                        fallbacks += "${kind.label}: ${e.message}"
                    } catch (e: CommandFailedException) {
                        fallbacks += "${kind.label}: ${e.message}"
                    }
                }
                out ?: throw IllegalStateException("No scan backend could refine: ${fallbacks.joinToString("; ")}")
            }
            lock.withLock {
                session.snapshot?.close()
                session.snapshot = null
                session.results = newResults
                session.step++
                session.lastMode = mode
                session.backend = usedBackend
                if (usedBackend == ScanBackendKind.TURBO) { session.turboEpoch = turboEpoch; turboOwner = session }
                else if (turboOwner === session) { session.turboEpoch = null }
            }
            if (activePid == pid) _session.value = session.view()
            finishStats(session, mode, 0, candidates, fallbacks)
            summary(session)
        }
    }

    /** Link estimate for the current connection (measured once per connection epoch). */
    private var link: Pair<Long, ScanEngine.LinkEstimate>? = null

    /** Last measured link (for display/diagnostics). */
    val linkEstimate: ScanEngine.LinkEstimate? get() = link?.second

    /** Applies the adaptive refine gap: measures RTT and bandwidth once per connection when possible. */
    private suspend fun tunedOptions(session: Session, prev: ScanResults, regions: List<MemoryRegion>?): ScanOptions {
        if (session.options.refineGap > 0) return session.options
        val epoch = transport?.connectionEpoch ?: 0L
        val known = link?.takeIf { it.first == epoch }?.second
        val est = known ?: run {
            if (regions == null || prev.count == 0) return session.options
            val r = regions.firstOrNull { it.isReadable && it.contains(prev.address(0)) } ?: return session.options
            runCatching { ScanEngine.measureLink(backend, session.pid, r) }.getOrNull()?.also { link = epoch to it }
        } ?: return session.options
        return session.options.copy(refineGap = est.breakEvenGap.coerceAtMost(session.options.refineSpan))
    }

    private fun order(pref: BackendPreference): List<ScanBackendKind> = when (pref) {
        BackendPreference.AUTO, BackendPreference.TURBO -> listOf(ScanBackendKind.TURBO, ScanBackendKind.SERVER, ScanBackendKind.CLIENT)
        BackendPreference.SERVER -> listOf(ScanBackendKind.SERVER, ScanBackendKind.CLIENT)
        BackendPreference.CLIENT -> listOf(ScanBackendKind.CLIENT)
    }

    private fun ineligible(kind: ScanBackendKind, matcher: Matcher, options: ScanOptions, first: Boolean): String? {
        if (kind == ScanBackendKind.CLIENT) return null
        val caps = transport?.capabilities ?: return "backend does not support server-side scans"
        if (!caps.isNg) return "connected payload is not ps4debug-NG"
        if (!caps.scanAuthorized) return "ps4debug-NG scan authorisation failed"
        if (kind == ScanBackendKind.TURBO && !caps.turboResident) return "Turbo Scan (server-resident) not offered by this build"
        return ServerScanner.ineligibility(matcher, options, first)
    }

    private suspend fun nextIneligible(kind: ScanBackendKind, session: Session, prev: ScanResults, matcher: Matcher, currentRegions: List<MemoryRegion>): String? {
        ineligible(kind, matcher, session.options, first = false)?.let { return it }
        return when (kind) {
            ScanBackendKind.TURBO -> when {
                session.turboEpoch == null -> "results are not held on the PS4"
                turboOwner !== session -> "PS4 session was replaced by another scan"
                session.turboEpoch != transport?.connectionEpoch -> "PS4 session lost after reconnect"
                else -> null
            }
            ScanBackendKind.SERVER -> when {
                prev.truncated -> "local result list is capped"
                session.options.backend == BackendPreference.AUTO && serverRefineCost(prev, matcher) >= ScanEngine.estimateRefineCost(prev, session.options.refineSpan, tunedOptions(session, prev, currentRegions).effectiveRefineGap) ->
                    "Android refine transfers fewer bytes for these candidates"
                else -> null
            }
            ScanBackendKind.CLIENT -> null
        }
    }

    /** Estimated cost of an iterative COUNT refine: candidate upload plus one round trip per 128 KiB chunk. */
    private fun serverRefineCost(prev: ScanResults, matcher: Matcher): Long {
        val entry = if (matcher.mode.needsPrevious) 4 + prev.type.size else 4
        val bytes = prev.count.toLong() * entry
        val chunks = bytes / com.ps4smartcheat.core.protocol.NgProtocol.SCAN_COUNT_MAX_CHUNK + 1
        return bytes + chunks * (32 + ScanEngine.ROUND_TRIP_COST_BYTES)
    }

    private fun finishStats(session: Session, mode: ScanMode, scanned: Long, candidates: Long, fallbacks: List<String>) {
        val r = session.results
        _lastStats.value = ScanStats(
            backend = session.backend ?: ScanBackendKind.CLIENT, mode = mode,
            durationMs = System.currentTimeMillis() - stepStart, scannedBytes = scanned, candidates = candidates,
            traffic = backend.traffic() - stepTraffic, results = r?.count ?: 0, serverTotal = r?.serverTotal, fallbacks = fallbacks,
        )
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset(pid: Int) {
        if (isRunning) cancel()
        sessions.remove(pid)?.let { releaseTurbo(it); it.dispose() }
        if (activePid == pid) _session.value = null
        _status.value = ScanStatus.Idle
    }

    /**
     * Ends the session of a process that exited or was replaced: its absolute addresses are meaningless now.
     * If it is the active session the status explains why.
     */
    fun invalidate(pid: Int, reason: String) {
        val s = sessions.remove(pid) ?: return
        if (activePid == pid && isRunning) cancel()
        releaseTurbo(s)
        s.dispose()
        if (activePid == pid) {
            _session.value = null
            _status.value = ScanStatus.Failed("Scan session ended: $reason")
        }
    }

    fun hasSession(pid: Int): Boolean = sessions.containsKey(pid)

    /** Drops every session (e.g. after disconnect: absolute addresses are meaningless in a new session). */
    fun resetAll() {
        cancel()
        sessions.values.forEach { releaseTurbo(it); it.dispose() }
        sessions.clear()
        _session.value = null
        _status.value = ScanStatus.Idle
    }

    fun results(pid: Int = activePid ?: -1): ScanResults? = sessions[pid]?.results

    /** Frees the PS4-side Turbo buffer when the owning session goes away. */
    private fun releaseTurbo(s: Session) {
        if (turboOwner === s) {
            turboOwner = null
            val srv = server ?: return
            if (s.turboEpoch != null && s.turboEpoch == transport?.connectionEpoch) scope.launch { srv.turboEnd() }
        }
    }

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

    private fun launchStep(@Suppress("UNUSED_PARAMETER") mode: ScanMode, block: suspend () -> String): Job {
        if (!running.compareAndSet(false, true)) throw IllegalStateException("A scan is already running")
        stepStart = System.currentTimeMillis()
        stepTraffic = backend.traffic()
        currentBackend = null
        _status.value = ScanStatus.Running(ScanProgress(0, 0, 0, "Starting"))
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val j = scope.launch {
            started.set(true)
            var final: ScanStatus = ScanStatus.Cancelled
            try {
                final = ScanStatus.Completed(block())
            } catch (e: CancellationException) {
                final = ScanStatus.Cancelled
                throw e
            } catch (e: Exception) {
                final = ScanStatus.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                running.set(false)
                _status.value = final
            }
        }
        // Covers cancellation before the coroutine body started (finally would not run then).
        j.invokeOnCompletion {
            if (!started.get() && running.compareAndSet(true, false)) _status.value = ScanStatus.Cancelled
        }
        job = j
        return j
    }

    private fun report(p: ScanProgress) {
        if (running.get()) _status.value = ScanStatus.Running(
            p.copy(
                elapsedMs = System.currentTimeMillis() - stepStart,
                backend = currentBackend,
                networkBytes = (backend.traffic() - stepTraffic).total,
            ),
        )
    }

    private fun summary(s: Session): String {
        s.snapshot?.let { return "Snapshot of ${it.totalBytes / 1024} KiB taken (${it.candidateCount} candidates). Change the value in game, then run a next scan." }
        val r = s.results ?: return "Done"
        return buildString {
            if (r.serverTotal != null && r.serverTotal > r.count) append("${r.serverTotal} on PS4, ${r.count} fetched after scan ${s.step}")
            else append("${r.count} result(s) after scan ${s.step}")
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
