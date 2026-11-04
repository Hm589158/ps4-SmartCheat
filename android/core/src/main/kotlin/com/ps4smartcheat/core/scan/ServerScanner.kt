package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.ProtocolException
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.protocol.NgProtocol
import com.ps4smartcheat.core.protocol.ServerScanTransport
import com.ps4smartcheat.core.protocol.Wire
import com.ps4smartcheat.core.util.LE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Scan execution backends, in default priority order. */
enum class ScanBackendKind(val label: String) {
    TURBO("Turbo Scan (PS4)"),
    SERVER("Server-side scan (PS4)"),
    CLIENT("Chunked scan (Android)"),
}

enum class BackendPreference(val label: String) {
    AUTO("Automatic"),
    TURBO("Prefer Turbo Scan"),
    SERVER("Prefer server-side scan"),
    CLIENT("Android only"),
}

/** The selected server-side path cannot run this request; the caller falls back to the next backend. */
class ServerScanUnavailableException(message: String) : com.ps4smartcheat.core.protocol.InSyncAbort(message)

/**
 * ps4debug-NG server-side scanning. Wire sequences follow `proc.c` (iterative START/COUNT) and
 * `scan_turbo.c` (Turbo START/COUNT/GET/END/CANCEL, server-resident + segment list). Only integer
 * types with natural alignment are sent to the server: NG compares floats with its own fuzzy policy,
 * which differs from this app's rounded matching, and the iterative COUNT window is only exact for
 * naturally aligned candidates.
 */
class ServerScanner(private val t: ServerScanTransport) {

    companion object {
        /** Max bytes requested per iterative START (keeps cancellation latency bounded). */
        const val ITERATIVE_SLICE = 64L shl 20
        /** Max segment length for Turbo (u32 length field). */
        const val TURBO_SEGMENT_MAX = 0xFFFF_0000L
        const val GET_PAGE = 65_536

        fun typeCode(type: ValueType): Int? = when (type) {
            ValueType.INT8 -> NgProtocol.VT_INT8
            ValueType.INT16 -> NgProtocol.VT_INT16
            ValueType.INT32 -> NgProtocol.VT_INT32
            ValueType.INT64 -> NgProtocol.VT_INT64
            else -> null
        }

        fun compareCode(mode: ScanMode): Int? = when (mode) {
            ScanMode.EXACT -> NgProtocol.CT_EXACT
            ScanMode.INCREASED -> NgProtocol.CT_INCREASED
            ScanMode.DECREASED -> NgProtocol.CT_DECREASED
            ScanMode.CHANGED -> NgProtocol.CT_CHANGED
            ScanMode.UNCHANGED -> NgProtocol.CT_UNCHANGED
            else -> null
        }

        /** Null when the request can run server-side, otherwise the reason it cannot. */
        fun ineligibility(matcher: Matcher, options: ScanOptions, first: Boolean): String? {
            if (typeCode(matcher.type) == null) return "${matcher.type.label} is compared on Android (server float policy differs)"
            if (compareCode(matcher.mode) == null) return "${matcher.mode.label} compares run on Android (no verified PS4-side code)"
            if (first && matcher.mode != ScanMode.EXACT) return "only exact first scans are offloaded"
            if (options.effectiveAlignment(matcher.type) != matcher.type.size) return "unaligned scans run on Android"
            return null
        }
    }

    private fun pattern(m: Matcher): ByteArray? = (m.value as? ParsedValue.IntValue)?.let { ValueCodec.encode(it) }

    // ------------------------------------------------------------------------------ iterative

    /** First scan with CMD_PROC_SCAN_START, one request per region slice. */
    suspend fun iterativeFirst(
        pid: Int, regions: List<MemoryRegion>, matcher: Matcher, options: ScanOptions, progress: ProgressListener,
    ): ScanResults {
        val vt = typeCode(matcher.type)!!
        val ct = compareCode(matcher.mode)!!
        val pat = pattern(matcher) ?: throw ServerScanUnavailableException("exact value required")
        val vl = matcher.type.size
        val builder = ResultsBuilder(matcher.type, options.maxResults)
        val total = regions.sumOf { it.size }
        var done = 0L
        progress(ScanProgress(0, total, 0, "Scanning on PS4"))
        for (r in regions) {
            var pos = r.start
            while (pos < r.end) {
                coroutineContext.ensureActive()
                val len = minOf(ITERATIVE_SLICE, r.end - pos) // multiple of 8 except at region end
                val start = pos
                t.exclusive("server scan", t.serverScanTimeoutMs) { w ->
                    val body = LE.buffer(NgProtocol.SCAN_START_PACKET_SIZE)
                        .putInt(pid).putLong(start).putInt(len.toInt()).put(vt.toByte()).put(ct.toByte()).put(0).putInt(vl).array()
                    w.send(w.header(NgProtocol.CMD_PROC_SCAN_START, body.size), body)
                    w.expectSuccess(NgProtocol.CMD_PROC_SCAN_START, w.readU32())
                    w.send(pat)
                    w.expectSuccess(NgProtocol.CMD_PROC_SCAN_START, w.readU32())
                    readRecordStream(w, vl) { off, buf, o ->
                        val a = start + (off.toLong() and 0xFFFFFFFFL)
                        if (a + vl <= start + len) builder.add(a, buf, o)
                    }
                    w.expectSuccess(NgProtocol.CMD_PROC_SCAN_START, w.readU32())
                }
                pos += len
                done += len
                progress(ScanProgress(done, total, builder.count.toLong(), "Scanning on PS4"))
            }
        }
        return builder.build()
    }

    /**
     * Refines [previous] with the client-driven CMD_PROC_SCAN_COUNT stream: only candidate offsets
     * (plus previous values when needed) go to the PS4, only matches come back.
     */
    suspend fun iterativeNext(
        pid: Int, previous: ScanResults, regions: List<MemoryRegion>, matcher: Matcher, options: ScanOptions,
        progress: ProgressListener,
    ): ScanResults {
        val vt = typeCode(matcher.type)!!
        val ct = compareCode(matcher.mode)!!
        val vl = matcher.type.size
        val needsPrev = matcher.mode.needsPrevious
        val pat = if (matcher.mode.needsValue) pattern(matcher) ?: throw ServerScanUnavailableException("value required") else null
        val entry = if (needsPrev) 4 + vl else 4
        val perChunk = NgProtocol.SCAN_COUNT_MAX_CHUNK / entry
        val alive = liveCandidates(previous, regions)
        val builder = ResultsBuilder(matcher.type, options.maxResults)
        val job = coroutineContext[Job]
        val total = previous.count.toLong()
        progress(ScanProgress(0, total, 0, "Refining on PS4"))
        val addrs = previous.addressArray()
        val values = previous.valuesArray()
        var i = 0
        var processed = 0L
        while (i < alive.size) {
            coroutineContext.ensureActive()
            // One COUNT command per group whose offsets fit the u32 offset field.
            val base = addrs[alive[i]]
            var j = i
            while (j < alive.size && addrs[alive[j]] - base <= 0xFFFF_FFFFL - vl) j++
            val from = i
            val to = j
            var cancelled = false
            t.exclusive("server refine", t.serverScanTimeoutMs) { w ->
                val body = LE.buffer(NgProtocol.SCAN_COUNT_PACKET_SIZE)
                    .putInt(pid).putLong(base).put(vt.toByte()).put(ct.toByte()).putInt(if (pat != null) vl else 0).array()
                w.send(w.header(NgProtocol.CMD_PROC_SCAN_COUNT, body.size), body)
                w.expectSuccess(NgProtocol.CMD_PROC_SCAN_COUNT, w.readU32())
                if (pat != null) w.send(pat)
                var k = from
                while (k < to) {
                    if (job?.isActive == false) { cancelled = true; break }
                    val n = minOf(perChunk, to - k)
                    val chunk = LE.buffer(4 + n * entry).putInt(n * entry)
                    for (q in k until k + n) {
                        val idx = alive[q]
                        chunk.putInt((addrs[idx] - base).toInt())
                        if (needsPrev) chunk.put(values, idx * vl, vl)
                    }
                    w.send(chunk.array())
                    readRecordStream(w, vl) { off, buf, o -> builder.add(base + (off.toLong() and 0xFFFFFFFFL), buf, o) }
                    k += n
                    processed += n
                    progress(ScanProgress(processed, total, builder.count.toLong(), "Refining on PS4"))
                }
                w.send(LE.buffer(4).putInt(-1).array())
                w.expectSuccess(NgProtocol.CMD_PROC_SCAN_COUNT, w.readU32())
            }
            if (cancelled) throw CancellationException("Scan cancelled")
            i = j
        }
        return builder.build()
    }

    /** Reads `u64 len` + `(u32 offset, value)` record batches until the all-ones sentinel. */
    private inline fun readRecordStream(w: Wire, vl: Int, onRecord: (Int, ByteArray, Int) -> Unit) {
        val rec = 4 + vl
        while (true) {
            val len = w.readU64()
            if (len == NgProtocol.SENTINEL) return
            if (len <= 0 || len > NgProtocol.SCAN_MAX_BATCH || len % rec != 0L) {
                w.invalidate()
                throw ProtocolException("Malformed scan result batch length $len")
            }
            val buf = w.readExactly(len.toInt())
            var o = 0
            while (o < buf.size) {
                onRecord(LE.i32(buf, o), buf, o + 4)
                o += rec
            }
        }
    }

    // ------------------------------------------------------------------------------ turbo

    /** Result of a Turbo step: locally fetched results plus the server-side survivor count. */
    class TurboStep(val results: ScanResults, val serverCount: Long, val epoch: Long)

    suspend fun turboFirst(
        pid: Int, regions: List<MemoryRegion>, matcher: Matcher, options: ScanOptions, progress: ProgressListener,
    ): TurboStep {
        val vt = typeCode(matcher.type)!!
        val pat = pattern(matcher) ?: throw ServerScanUnavailableException("exact value required")
        val vl = matcher.type.size
        val segs = ArrayList<Pair<Long, Long>>()
        for (r in regions) {
            var p = r.start
            while (p < r.end) {
                val len = minOf(TURBO_SEGMENT_MAX, r.end - p)
                segs += p to len
                p += len
            }
        }
        if (segs.isEmpty()) throw ServerScanUnavailableException("no regions")
        if (segs.size > NgProtocol.TURBO_MAX_SEGMENTS) throw ServerScanUnavailableException("too many segments")
        val total = regions.sumOf { it.size }
        val caps = t.capabilities
        val flags = NgProtocol.TS_SERVER_RESIDENT or NgProtocol.TS_SNAPSHOT_SEGMENTS or
            (if (options.turboAliasing && caps.turboAliasing) NgProtocol.TS_USE_ALIASING else 0)
        progress(ScanProgress(0, total, 0, "Turbo scan on PS4", indeterminate = true))
        var epoch = 0L
        val count = withServerCancel(pid) {
            t.exclusive("turbo scan", t.serverScanTimeoutMs) { w ->
                epoch = t.connectionEpoch
                val body = LE.buffer(NgProtocol.TURBO_START_PACKET_SIZE)
                    .putInt(pid).putLong(segs[0].first).putInt(segs[0].second.toInt())
                    .put(vt.toByte()).put(NgProtocol.CT_EXACT.toByte()).put(0).putInt(vl).putInt(flags).array()
                w.send(w.header(NgProtocol.TURBO_START, body.size), body)
                w.expectSuccess(NgProtocol.TURBO_START, w.readU32())
                w.send(pat)
                w.expectSuccess(NgProtocol.TURBO_START, w.readU32())
                val segBuf = LE.buffer(4 + segs.size * 12).putInt(segs.size)
                for ((a, l) in segs) segBuf.putLong(a).putInt(l.toInt())
                w.send(segBuf.array())
                val summary = w.readExactly(12)
                val stored = LE.i32(summary, 0)
                val n = LE.i64(summary, 4)
                if (stored != 1) {
                    if (w.readU64() != NgProtocol.SENTINEL) { w.invalidate(); throw ProtocolException("Turbo START: missing sentinel") }
                    w.expectSuccess(NgProtocol.TURBO_START, w.readU32())
                    throw ServerScanUnavailableException("PS4 resident result buffer full (too many matches to keep on the console)")
                }
                w.expectSuccess(NgProtocol.TURBO_START, w.readU32())
                if (n < 0) { w.invalidate(); throw ProtocolException("Turbo START: invalid count $n") }
                n
            }
        }
        progress(ScanProgress(total, total, count, "Fetching results"))
        return TurboStep(turboFetch(matcher.type, count, options), count, epoch)
    }

    suspend fun turboNext(pid: Int, matcher: Matcher, options: ScanOptions, progress: ProgressListener): TurboStep {
        val vt = typeCode(matcher.type)!!
        val ct = compareCode(matcher.mode)!!
        val vl = matcher.type.size
        val pat = if (matcher.mode.needsValue) pattern(matcher) ?: throw ServerScanUnavailableException("value required") else null
        val flags = NgProtocol.TS_SERVER_RESIDENT or
            (if (options.turboAliasing && t.capabilities.turboRescanAliasing) NgProtocol.TS_RESCAN_ALIASING else 0)
        progress(ScanProgress(0, 0, 0, "Turbo refine on PS4", indeterminate = true))
        var epoch = 0L
        val count = withServerCancel(pid) {
            t.exclusive("turbo refine", t.serverScanTimeoutMs) { w ->
                epoch = t.connectionEpoch
                val body = LE.buffer(NgProtocol.TURBO_COUNT_PACKET_SIZE)
                    .putInt(pid).putLong(0).put(vt.toByte()).put(ct.toByte()).putInt(if (pat != null) vl else 0).putInt(flags).array()
                w.send(w.header(NgProtocol.TURBO_COUNT, body.size), body)
                w.expectSuccess(NgProtocol.TURBO_COUNT, w.readU32())
                if (pat != null) w.send(pat)
                if (w.readU64() != NgProtocol.SENTINEL) { w.invalidate(); throw ProtocolException("Turbo COUNT: missing progress sentinel") }
                val n = w.readU64()
                w.expectSuccess(NgProtocol.TURBO_COUNT, w.readU32())
                if (n < 0) { w.invalidate(); throw ProtocolException("Turbo COUNT: invalid count $n") }
                n
            }
        }
        return TurboStep(turboFetch(matcher.type, count, options), count, epoch)
    }

    /** Frees the server-resident session (best effort). */
    suspend fun turboEnd() {
        runCatching {
            t.exclusive("turbo end") { w ->
                w.send(w.header(NgProtocol.TURBO_END, 0))
                w.expectSuccess(NgProtocol.TURBO_END, w.readU32())
            }
        }
    }

    /** Pulls up to maxResults survivors with Turbo GET in pages. */
    private suspend fun turboFetch(type: ValueType, count: Long, options: ScanOptions): ScanResults {
        val vl = type.size
        val want = minOf(count, options.maxResults.toLong(), options.turboFetchMax.coerceAtLeast(1).toLong()).toInt()
        val builder = ResultsBuilder(type, options.maxResults)
        var start = 0
        while (start < want) {
            coroutineContext.ensureActive()
            val n = minOf(GET_PAGE, want - start)
            val s0 = start
            val got = t.exclusive("turbo fetch") { w ->
                val body = LE.buffer(NgProtocol.TURBO_GET_PACKET_SIZE).putInt(s0).putInt(n).putInt(0).array()
                w.send(w.header(NgProtocol.TURBO_GET, body.size), body)
                w.expectSuccess(NgProtocol.TURBO_GET, w.readU32())
                val hdr = w.readU32()
                val records = (hdr and 0x7FFFFFFF).toInt()
                val rec = 8 + vl * (if (hdr and 0x80000000L != 0L) 3 else 2)
                if (records > n) { w.invalidate(); throw ProtocolException("Turbo GET returned $records > $n records") }
                val buf = w.readExactly(records * rec)
                var o = 0
                repeat(records) {
                    builder.add(LE.i64(buf, o), buf, o + 8)
                    o += rec
                }
                w.expectSuccess(NgProtocol.TURBO_GET, w.readU32())
                records
            }
            if (got == 0) break
            start += got
        }
        return builder.build(serverTotal = count)
    }

    /**
     * Runs a blocking Turbo exchange; if the coroutine is cancelled meanwhile, sends CANCEL for [pid] on a
     * second connection so the PS4 stops early (the busy connection cannot receive commands). The server
     * session is indeterminate after a cancel, so it is ended before the cancellation propagates.
     */
    private suspend fun <T> withServerCancel(pid: Int, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) withContext(NonCancellable + Dispatchers.IO) {
                    t.sendSideChannel(NgProtocol.TURBO_CANCEL, LE.buffer(4).putInt(pid).array())
                }
            }
        }
        try {
            val r = block()
            finished.set(true)
            r
        } catch (e: CancellationException) {
            finished.set(true)
            withContext(NonCancellable) { turboEnd() }
            throw e
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    /** Indices of candidates still inside a readable region (unmapped ones would read as zeros). */
    private fun liveCandidates(previous: ScanResults, regions: List<MemoryRegion>): IntArray {
        val rs = regions.filter { it.isValid && it.isReadable }.sortedBy { it.start }
        val starts = LongArray(rs.size) { rs[it].start }
        val out = IntArray(previous.count)
        var n = 0
        val size = previous.type.size
        for (i in 0 until previous.count) {
            val a = previous.address(i)
            var idx = java.util.Arrays.binarySearch(starts, a)
            if (idx < 0) idx = -idx - 2
            if (idx >= 0 && rs[idx].contains(a, size)) out[n++] = i
        }
        return out.copyOf(n)
    }
}
