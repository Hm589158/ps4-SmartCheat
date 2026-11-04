package com.ps4smartcheat.core.watch

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.pointer.PointerResolution
import com.ps4smartcheat.core.pointer.PointerResolver
import com.ps4smartcheat.core.util.Address

enum class WatchStatus(val label: String, val readable: Boolean) {
    /** Resolved through its module base (e.g. eboot.bin+0x120) in the current process. */
    MODULE_RELATIVE("module-relative", true),
    /** Absolute address in the same process (same pid) it was added from. */
    SAME_SESSION("same session", true),
    /** Absolute address from an earlier session, still mapped now; the value may be something else. */
    ABSOLUTE_UNVERIFIED("from an earlier session — verify", true),
    /** The address is not inside a readable region of the current process. Never read. */
    UNMAPPED("not mapped in this process", false),
    /** Pointer chain followed in the current process. */
    POINTER("pointer chain", true),
    /** Pointer chain not followed yet (not connected) — never read. */
    POINTER_UNRESOLVED("pointer chain (not resolved yet)", false),
    /** Pointer chain leads to unmapped memory now (e.g. the object does not exist at the moment). */
    POINTER_BROKEN("pointer chain broken", false),
}

data class ResolvedWatch(
    val entry: WatchEntry,
    /** Address in the current process. */
    val address: Long,
    val status: WatchStatus,
    val region: MemoryRegion?,
)

object WatchResolver {
    /** Resolves [entry] against the memory map of the current process [pid]. Pure; does not touch the console. */
    fun resolve(entry: WatchEntry, pid: Int, regions: List<MemoryRegion>): ResolvedWatch {
        if (entry.pointer != null) return ResolvedWatch(entry, entry.address, WatchStatus.POINTER_UNRESOLVED, null)
        val viaModule = entry.module?.let { ModuleResolver.resolve(it, regions) }
        val address = viaModule ?: entry.address
        val region = regions.regionFor(address, entry.type.size)?.takeIf { it.isReadable }
        val status = when {
            region == null -> WatchStatus.UNMAPPED
            viaModule != null -> WatchStatus.MODULE_RELATIVE
            entry.discoveredPid == pid -> WatchStatus.SAME_SESSION
            else -> WatchStatus.ABSOLUTE_UNVERIFIED
        }
        return ResolvedWatch(entry, address, status, region)
    }

    fun resolveAll(entries: List<WatchEntry>, pid: Int, regions: List<MemoryRegion>): List<ResolvedWatch> =
        entries.map { resolve(it, pid, regions) }

    /** Like [resolveAll], and follows pointer chains by reading the pointers (mapped memory only). */
    suspend fun resolveLive(backend: DebugBackend, pid: Int, entries: List<WatchEntry>, regions: List<MemoryRegion>): List<ResolvedWatch> =
        entries.map { e ->
            val chain = e.pointer ?: return@map resolve(e, pid, regions)
            when (val r = PointerResolver.resolve(backend, pid, chain, regions, e.type.size)) {
                is PointerResolution.Resolved -> ResolvedWatch(e, r.address, WatchStatus.POINTER, regions.regionFor(r.address, e.type.size))
                is PointerResolution.Broken -> ResolvedWatch(e, e.address, WatchStatus.POINTER_BROKEN, null)
            }
        }
}

/**
 * Reads the current values of an address list with as few CMD_PROC_READ requests as possible: nearby entries in
 * the same region are merged into one read. A read never crosses a region boundary (ps4debug fails reads that
 * touch unmapped memory) and unmapped entries are never read.
 */
class WatchReader(
    private val maxGap: Int = 256,
    private val maxSpan: Int = 64 * 1024,
) {
    data class Span(val start: Long, val length: Int, val members: List<ResolvedWatch>)

    fun plan(items: List<ResolvedWatch>): List<Span> {
        val readable = items.filter { it.status.readable && it.region != null }
            .sortedWith { a, b -> java.lang.Long.compareUnsigned(a.address, b.address) }
        val spans = mutableListOf<Span>()
        var start = 0L
        var end = 0L
        var region: MemoryRegion? = null
        val members = mutableListOf<ResolvedWatch>()
        fun flush() {
            if (members.isNotEmpty()) spans += Span(start, (end - start).toInt(), members.toList())
            members.clear()
        }
        for (w in readable) {
            val wEnd = w.address + w.entry.type.size
            val fits = members.isNotEmpty() && w.region == region &&
                w.address - end <= maxGap && maxOf(end, wEnd) - start <= maxSpan
            if (!fits) {
                flush()
                start = w.address
                end = wEnd
                region = w.region
            } else if (Address.lt(end, wEnd)) {
                end = wEnd
            }
            members += w
        }
        flush()
        return spans
    }

    /** Current bytes per entry id; unmapped entries are absent. */
    suspend fun read(backend: DebugBackend, pid: Int, items: List<ResolvedWatch>): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        for (span in plan(items)) {
            val data = backend.readMemory(pid, span.start, span.length)
            for (m in span.members) {
                val off = (m.address - span.start).toInt()
                if (off >= 0 && off + m.entry.type.size <= data.size) out[m.entry.id] = data.copyOfRange(off, off + m.entry.type.size)
            }
        }
        return out
    }
}
