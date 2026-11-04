package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.model.MemoryRegion
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Byte storage for an "unknown initial value" snapshot. */
interface SnapshotStorage : Closeable {
    fun write(position: Long, data: ByteArray, offset: Int, length: Int)
    fun read(position: Long, dst: ByteArray, offset: Int, length: Int)
}

class InMemorySnapshotStorage(size: Long) : SnapshotStorage {
    init {
        require(size in 0..MAX) { "In-memory snapshots are limited to ${MAX / (1024 * 1024)} MiB" }
    }
    private val buf = ByteArray(size.toInt())
    override fun write(position: Long, data: ByteArray, offset: Int, length: Int) =
        System.arraycopy(data, offset, buf, position.toInt(), length)
    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int) =
        System.arraycopy(buf, position.toInt(), dst, offset, length)
    override fun close() {}

    companion object {
        const val MAX = 256L * 1024 * 1024
    }
}

/** Disk-backed snapshot (app cache directory). The file is deleted on [close]. */
class FileSnapshotStorage(private val file: File) : SnapshotStorage {
    private val raf = RandomAccessFile(file, "rw")
    override fun write(position: Long, data: ByteArray, offset: Int, length: Int) {
        raf.seek(position); raf.write(data, offset, length)
    }
    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int) {
        raf.seek(position); raf.readFully(dst, offset, length)
    }
    override fun close() {
        runCatching { raf.close() }
        file.delete()
    }
}

/** A full copy of the scanned regions, taken by an unknown-initial-value first scan. */
class MemorySnapshot internal constructor(
    val type: ValueType,
    val alignment: Int,
    val regions: List<MemoryRegion>,
    /** Start offset of each region inside [storage]. */
    internal val offsets: LongArray,
    internal val storage: SnapshotStorage,
) : Closeable {
    val totalBytes: Long = regions.sumOf { it.size }

    /** Number of candidate addresses represented by the snapshot. */
    val candidateCount: Long = regions.sumOf { r -> ScanEngine.alignedCount(r.start, r.end, type.size, alignment) }

    override fun close() = storage.close()
}
