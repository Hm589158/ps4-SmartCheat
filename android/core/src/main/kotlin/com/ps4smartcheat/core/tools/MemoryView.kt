package com.ps4smartcheat.core.tools

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.model.MemoryRegion

/** One 16-byte line of a hex dump. */
class HexRow(
    val address: Long,
    val bytes: ByteArray,
    /** Byte differs from the previous read of the same address. */
    val changed: BooleanArray,
    /** Byte lies inside a readable mapped region (unmapped bytes are shown as "??"). */
    val mapped: BooleanArray,
) {
    fun hex(i: Int): String = if (!mapped[i]) "??" else "%02X".format(bytes[i].toInt() and 0xFF)

    fun ascii(): String = buildString {
        for (i in bytes.indices) append(if (mapped[i]) HexDump.printable(bytes[i]) else ' ')
    }
}

/** A window of process memory read for display. */
class MemoryWindow(val start: Long, val bytes: ByteArray, val mapped: BooleanArray) {
    val end: Long get() = start + bytes.size
}

object HexDump {
    const val ROW = 16

    fun alignDown(address: Long, alignment: Int = ROW): Long = address - Math.floorMod(address, alignment.toLong())

    fun printable(b: Byte): Char = if (b in 0x20..0x7E) b.toInt().toChar() else '.'

    /**
     * Splits [window] into 16-byte rows. [previous] (an earlier window) marks changed bytes where both reads
     * were mapped and cover the same address.
     */
    fun rows(window: MemoryWindow, previous: MemoryWindow?, rowSize: Int = ROW): List<HexRow> {
        require(rowSize in 1..64)
        val out = ArrayList<HexRow>((window.bytes.size + rowSize - 1) / rowSize)
        var off = 0
        while (off < window.bytes.size) {
            val n = minOf(rowSize, window.bytes.size - off)
            val bytes = window.bytes.copyOfRange(off, off + n)
            val mapped = window.mapped.copyOfRange(off, off + n)
            val changed = BooleanArray(n) { i ->
                val a = window.start + off + i
                if (previous == null || !mapped[i] || a < previous.start || a >= previous.end) false
                else {
                    val po = (a - previous.start).toInt()
                    previous.mapped[po] && previous.bytes[po] != bytes[i]
                }
            }
            out += HexRow(window.start + off, bytes, changed, mapped)
            off += n
        }
        return out
    }

    /** Number of changed bytes between two windows (for "x bytes changed" summaries). */
    fun changedCount(rows: List<HexRow>): Int = rows.sumOf { r -> r.changed.count { it } }
}

/**
 * Reads display windows without touching unmapped memory: ps4debug returns zeros for unmapped pages,
 * which would be indistinguishable from real zeros, so only readable region parts are requested.
 */
class MemoryViewer(private val backend: DebugBackend) {
    suspend fun read(pid: Int, start: Long, length: Int, regions: List<MemoryRegion>): MemoryWindow {
        require(length in 1..(1 shl 20)) { "length out of range" }
        val bytes = ByteArray(length)
        val mapped = BooleanArray(length)
        val end = start + length
        for (r in regions) {
            if (!r.isValid || !r.isReadable) continue
            val s = maxOf(start, r.start)
            val e = minOf(end, r.end)
            if (s >= e) continue
            val chunk = backend.readMemory(pid, s, (e - s).toInt())
            System.arraycopy(chunk, 0, bytes, (s - start).toInt(), chunk.size)
            java.util.Arrays.fill(mapped, (s - start).toInt(), (e - start).toInt(), true)
        }
        return MemoryWindow(start, bytes, mapped)
    }
}
