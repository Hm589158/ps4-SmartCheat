package com.ps4smartcheat.core.scan

/** Test helper: builds [ScanResults] directly (ResultsBuilder is internal to :core). */
object ResultsBuilderAccess {
    fun build(type: ValueType, entries: List<Pair<Long, Int>>): ScanResults {
        val b = ResultsBuilder(type, 1000)
        for ((a, v) in entries) b.add(a, ValueCodec.encodeLong(type, v.toLong()), 0)
        return b.build()
    }
}
