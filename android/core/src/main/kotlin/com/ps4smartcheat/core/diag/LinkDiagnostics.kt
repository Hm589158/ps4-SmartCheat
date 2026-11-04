package com.ps4smartcheat.core.diag

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.DebugBackend
import kotlinx.coroutines.delay

/** Round-trip times of [samples] CMD_VERSION requests (read-only; ps4debug answers it without side effects). */
data class PingStats(val samples: List<Double>, val failures: Int) {
    val minMs: Double? get() = samples.minOrNull()
    val maxMs: Double? get() = samples.maxOrNull()
    val avgMs: Double? get() = if (samples.isEmpty()) null else samples.average()
    /** Mean absolute difference between consecutive samples (a simple jitter figure). */
    val jitterMs: Double? get() = if (samples.size < 2) null else samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()
}

object LinkDiagnostics {
    suspend fun ping(backend: DebugBackend, count: Int = 5, pauseMs: Long = 100, clock: () -> Long = System::nanoTime): PingStats {
        require(count in 1..50) { "count must be 1..50" }
        val out = ArrayList<Double>()
        var failures = 0
        repeat(count) { i ->
            val t0 = clock()
            try {
                backend.ping()
                out += (clock() - t0) / 1_000_000.0
            } catch (e: BackendException) {
                failures++
            }
            if (i < count - 1) delay(pauseMs)
        }
        return PingStats(out, failures)
    }
}
