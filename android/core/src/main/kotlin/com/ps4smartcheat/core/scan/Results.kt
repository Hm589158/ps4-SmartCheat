package com.ps4smartcheat.core.scan

/**
 * Compact, immutable scan result list: addresses (ascending) plus the value bytes observed at
 * the time of the scan. Memory use is `count * (8 + type.size)` bytes.
 */
class ScanResults internal constructor(
    val type: ValueType,
    private val addresses: LongArray,
    private val values: ByteArray,
    val count: Int,
    /** True if more matches existed than [ScanOptions.maxResults]; only the first ones were kept. */
    val truncated: Boolean,
) {
    fun address(i: Int): Long {
        checkIndex(i); return addresses[i]
    }

    /** Copies the stored value of result [i] into a new array. */
    fun valueBytes(i: Int): ByteArray {
        checkIndex(i)
        return values.copyOfRange(i * type.size, (i + 1) * type.size)
    }

    internal fun valuesArray(): ByteArray = values
    internal fun addressArray(): LongArray = addresses

    fun page(pageIndex: Int, pageSize: Int): IntRange {
        require(pageSize > 0)
        val from = pageIndex.toLong() * pageSize
        if (from >= count || pageIndex < 0) return IntRange.EMPTY
        return from.toInt() until minOf(count.toLong(), from + pageSize).toInt()
    }

    val pageCountFor: (Int) -> Int = { size -> if (count == 0) 0 else (count + size - 1) / size }

    val estimatedBytes: Long get() = count.toLong() * (8 + type.size)

    private fun checkIndex(i: Int) {
        if (i < 0 || i >= count) throw IndexOutOfBoundsException("result $i of $count")
    }

    companion object {
        fun empty(type: ValueType) = ScanResults(type, LongArray(0), ByteArray(0), 0, false)
    }
}

/** Growable builder enforcing a hard result cap. */
internal class ResultsBuilder(private val type: ValueType, private val limit: Int) {
    private var addresses = LongArray(256)
    private var values = ByteArray(256 * type.size)
    var count = 0
        private set
    var truncated = false
        private set

    val isFull: Boolean get() = count >= limit

    /** @return false if the cap was hit and the value was not stored. */
    fun add(address: Long, src: ByteArray, off: Int): Boolean {
        if (count >= limit) {
            truncated = true
            return false
        }
        if (count == addresses.size) grow()
        addresses[count] = address
        System.arraycopy(src, off, values, count * type.size, type.size)
        count++
        return true
    }

    private fun grow() {
        val newCap = minOf(limit.toLong(), addresses.size.toLong() * 2).toInt().coerceAtLeast(addresses.size + 1)
        addresses = addresses.copyOf(newCap)
        values = values.copyOf(newCap * type.size)
    }

    fun build(): ScanResults =
        ScanResults(type, addresses.copyOf(count), values.copyOf(count * type.size), count, truncated)
}
