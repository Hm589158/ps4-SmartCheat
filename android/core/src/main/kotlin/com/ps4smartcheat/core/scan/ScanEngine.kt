package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.model.MemoryRegion
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class ScanOptions(
    /** Bytes per CMD_PROC_READ during full-region scans. */
    val chunkSize: Int = 1 shl 20,
    /** Maximum span read at once when refining existing results. */
    val refineSpan: Int = 64 * 1024,
    /** Hard cap on stored results (bounds memory: count * (8 + type size)). */
    val maxResults: Int = 1_000_000,
    /** Address alignment for candidates; 0 = natural alignment of the type. */
    val alignment: Int = 0,
    /** Maximum snapshot size for unknown-initial-value scans. */
    val maxSnapshotBytes: Long = 512L * 1024 * 1024,
) {
    fun effectiveAlignment(type: ValueType): Int = if (alignment <= 0) type.size else alignment

    init {
        require(chunkSize in 16..(16 shl 20)) { "chunkSize out of range" }
        require(refineSpan in 16..(16 shl 20)) { "refineSpan out of range" }
        require(maxResults > 0) { "maxResults must be > 0" }
        require(alignment in 0..8) { "alignment must be 0..8" }
    }
}

/** Which regions take part in a scan. Defaults target game data: readable + writable, not executable. */
data class RegionFilter(
    val includeReadOnly: Boolean = false,
    val includeExecutable: Boolean = false,
    /** Skip regions larger than this (0 = no limit). */
    val maxRegionBytes: Long = 0,
) {
    fun accepts(r: MemoryRegion): Boolean {
        if (!r.isValid || !r.isReadable) return false
        if (!includeReadOnly && !r.isWritable) return false
        if (!includeExecutable && r.isExecutable) return false
        if (maxRegionBytes > 0 && r.size > maxRegionBytes) return false
        return true
    }

    /** Filtered, sorted by start address, overlapping duplicates removed. */
    fun select(regions: List<MemoryRegion>): List<MemoryRegion> {
        val sorted = regions.filter(::accepts).sortedWith { a, b -> java.lang.Long.compareUnsigned(a.start, b.start) }
        val out = ArrayList<MemoryRegion>(sorted.size)
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && java.lang.Long.compareUnsigned(r.start, last.end) < 0) {
                // Overlap (should not happen in a VM map): keep only the non-overlapping tail.
                if (java.lang.Long.compareUnsigned(r.end, last.end) > 0) out += r.copy(start = last.end)
            } else {
                out += r
            }
        }
        return out
    }
}

data class ScanProgress(val bytesDone: Long, val bytesTotal: Long, val matches: Long, val stage: String) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
}

typealias ProgressListener = (ScanProgress) -> Unit

class ScanTooLargeException(message: String) : IllegalStateException(message)

/**
 * Stateless scanning algorithms. All reads stay inside the supplied regions, are chunked, and are
 * cancellable between chunks.
 */
object ScanEngine {

    fun alignUp(address: Long, alignment: Int): Long {
        val r = Math.floorMod(address, alignment.toLong())
        return if (r == 0L) address else address + (alignment - r)
    }

    /** Number of aligned addresses `a` with `start <= a` and `a + size <= end`. */
    fun alignedCount(start: Long, end: Long, size: Int, alignment: Int): Long {
        val first = alignUp(start, alignment)
        val lastStart = end - size
        if (lastStart < first) return 0
        return (lastStart - first) / alignment + 1
    }

    /** First scan over full regions comparing against an exact value. */
    suspend fun firstScan(
        backend: DebugBackend,
        pid: Int,
        regions: List<MemoryRegion>,
        matcher: Matcher,
        options: ScanOptions = ScanOptions(),
        progress: ProgressListener = {},
    ): ScanResults {
        require(!matcher.mode.needsPrevious) { "${matcher.mode.label} needs a previous scan" }
        val type = matcher.type
        val size = type.size
        val align = options.effectiveAlignment(type)
        val total = regions.sumOf { it.size }
        val builder = ResultsBuilder(type, options.maxResults)
        var done = 0L
        progress(ScanProgress(0, total, 0, "Scanning"))
        regionLoop@ for (region in regions) {
            var pos = region.start
            var next = alignUp(region.start, align)
            while (pos < region.end && next + size <= region.end) {
                coroutineContext.ensureActive()
                val len = minOf(options.chunkSize.toLong(), region.end - pos).toInt()
                val buf = backend.readMemory(pid, pos, len)
                val bufEnd = pos + len
                var a = next
                while (a + size <= bufEnd) {
                    val off = (a - pos).toInt()
                    if (matcher.matches(buf, off, null, 0)) {
                        if (!builder.add(a, buf, off)) break@regionLoop
                    }
                    a += align
                }
                next = a
                // Continue at the first unevaluated candidate: values crossing the chunk boundary are
                // re-read in the next chunk; with natural alignment this adds no overlap at all.
                val newPos = if (bufEnd >= region.end) region.end else minOf(next, bufEnd)
                done += newPos - pos
                pos = newPos
                progress(ScanProgress(done, total, builder.count.toLong(), "Scanning"))
            }
            done += region.end - pos
        }
        return builder.build()
    }

    /** Takes a snapshot of [regions] for an unknown-initial-value scan. */
    suspend fun snapshot(
        backend: DebugBackend,
        pid: Int,
        regions: List<MemoryRegion>,
        type: ValueType,
        options: ScanOptions,
        storageFactory: (Long) -> SnapshotStorage,
        progress: ProgressListener = {},
    ): MemorySnapshot {
        val total = regions.sumOf { it.size }
        if (total > options.maxSnapshotBytes) {
            throw ScanTooLargeException(
                "Unknown-value scan needs ${total / (1024 * 1024)} MiB, limit is ${options.maxSnapshotBytes / (1024 * 1024)} MiB. " +
                    "Use an exact value or narrow the region filter.",
            )
        }
        val storage = storageFactory(total)
        val offsets = LongArray(regions.size)
        var done = 0L
        try {
            regions.forEachIndexed { i, region ->
                offsets[i] = done
                var pos = region.start
                while (pos < region.end) {
                    coroutineContext.ensureActive()
                    val len = minOf(options.chunkSize.toLong(), region.end - pos).toInt()
                    val buf = backend.readMemory(pid, pos, len)
                    storage.write(done, buf, 0, len)
                    pos += len
                    done += len
                    progress(ScanProgress(done, total, 0, "Snapshot"))
                }
            }
        } catch (t: Throwable) {
            storage.close()
            throw t
        }
        return MemorySnapshot(type, options.effectiveAlignment(type), regions, offsets, storage)
    }

    /** Compares current memory against a snapshot. */
    suspend fun compareSnapshot(
        backend: DebugBackend,
        pid: Int,
        snapshot: MemorySnapshot,
        currentRegions: List<MemoryRegion>,
        matcher: Matcher,
        options: ScanOptions,
        progress: ProgressListener = {},
    ): ScanResults {
        val type = snapshot.type
        require(matcher.type == type) { "Type mismatch with snapshot" }
        val size = type.size
        val align = snapshot.alignment
        val builder = ResultsBuilder(type, options.maxResults)
        val total = snapshot.totalBytes
        var done = 0L
        regionLoop@ for ((i, region) in snapshot.regions.withIndex()) {
            // Skip regions that were unmapped since the snapshot.
            val stillMapped = currentRegions.any { it.isReadable && it.start <= region.start && it.end >= region.end }
            if (!stillMapped) {
                done += region.size
                continue
            }
            var pos = region.start
            var next = alignUp(region.start, align)
            while (pos < region.end && next + size <= region.end) {
                coroutineContext.ensureActive()
                val len = minOf(options.chunkSize.toLong(), region.end - pos).toInt()
                val cur = backend.readMemory(pid, pos, len)
                val old = ByteArray(len)
                snapshot.storage.read(snapshot.offsets[i] + (pos - region.start), old, 0, len)
                val bufEnd = pos + len
                var a = next
                while (a + size <= bufEnd) {
                    val off = (a - pos).toInt()
                    if (matcher.matches(cur, off, old, off)) {
                        if (!builder.add(a, cur, off)) break@regionLoop
                    }
                    a += align
                }
                next = a
                val newPos = if (bufEnd >= region.end) region.end else minOf(next, bufEnd)
                done += newPos - pos
                pos = newPos
                progress(ScanProgress(done, total, builder.count.toLong(), "Comparing"))
            }
            done += region.end - pos
        }
        return builder.build()
    }

    /**
     * Refines previous results by reading only the addresses that are still candidates.
     * Candidates are batched into spans of at most [ScanOptions.refineSpan] bytes that never cross a
     * region boundary. Candidates whose memory is no longer mapped are dropped.
     */
    suspend fun nextScan(
        backend: DebugBackend,
        pid: Int,
        previous: ScanResults,
        currentRegions: List<MemoryRegion>,
        matcher: Matcher,
        options: ScanOptions = ScanOptions(),
        progress: ProgressListener = {},
    ): ScanResults {
        val type = previous.type
        require(matcher.type == type) { "Type mismatch with previous results" }
        val size = type.size
        val regions = currentRegions.filter { it.isValid && it.isReadable }
            .sortedWith { a, b -> java.lang.Long.compareUnsigned(a.start, b.start) }
        val starts = LongArray(regions.size) { regions[it].start }
        val addrs = previous.addressArray()
        val prevValues = previous.valuesArray()
        val builder = ResultsBuilder(type, options.maxResults)
        val total = previous.count.toLong()
        progress(ScanProgress(0, total, 0, "Refining"))

        var i = 0
        while (i < previous.count) {
            coroutineContext.ensureActive()
            val first = addrs[i]
            val region = findRegion(regions, starts, first, size)
            if (region == null) {
                i++ // memory no longer mapped -> candidate is gone
                continue
            }
            // Extend the batch while the next candidate fits in the span and the same region.
            var j = i + 1
            val spanLimit = first + options.refineSpan
            while (j < previous.count) {
                val a = addrs[j]
                if (a + size > spanLimit || a + size > region.end || a < first) break
                j++
            }
            val spanEnd = addrs[j - 1] + size
            val buf = backend.readMemory(pid, first, (spanEnd - first).toInt())
            for (k in i until j) {
                val off = (addrs[k] - first).toInt()
                if (matcher.matches(buf, off, prevValues, k * size)) builder.add(addrs[k], buf, off)
            }
            i = j
            progress(ScanProgress(i.toLong(), total, builder.count.toLong(), "Refining"))
        }
        return builder.build()
    }

    private fun findRegion(regions: List<MemoryRegion>, starts: LongArray, address: Long, size: Int): MemoryRegion? {
        var idx = java.util.Arrays.binarySearch(starts, address)
        if (idx < 0) idx = -idx - 2
        if (idx < 0) return null
        val r = regions[idx]
        return if (r.contains(address, size)) r else null
    }
}
