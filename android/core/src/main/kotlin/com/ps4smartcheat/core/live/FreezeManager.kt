package com.ps4smartcheat.core.live

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.backend.WriteStatus
import com.ps4smartcheat.core.edit.EditPolicy
import com.ps4smartcheat.core.edit.WritePlan
import com.ps4smartcheat.core.scan.ValueType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class FrozenValue(
    val id: Long,
    val pid: Int,
    val processName: String,
    val address: Long,
    val type: ValueType,
    val bytes: ByteArray,
    val label: String,
)

/**
 * Client-side "freeze": periodically rewrites values while the Android app stays connected.
 * This is NOT an on-console freeze — it stops as soon as the connection drops, editing is disabled,
 * or the target process disappears. Values are never re-applied automatically after a reconnect.
 */
class FreezeManager(
    private val backend: DebugBackend,
    private val scope: CoroutineScope,
    private val policy: () -> EditPolicy,
) {
    private val _frozen = MutableStateFlow<List<FrozenValue>>(emptyList())
    val frozen: StateFlow<List<FrozenValue>> = _frozen.asStateFlow()

    private val _lastStopReason = MutableStateFlow<String?>(null)
    val lastStopReason: StateFlow<String?> = _lastStopReason.asStateFlow()

    @Volatile var intervalMs: Long = 250
        set(value) { field = value.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS) }

    /** Every N ticks the process list is checked so freezing stops when the game exits. */
    var processCheckEveryTicks = 20

    private val ids = AtomicLong()
    private var job: Job? = null

    /** Freezes a value from a validated [WritePlan] (validation already applied the edit policy). */
    fun freeze(plan: WritePlan, label: String): FrozenValue {
        check(policy().editingEnabled) { "Read-only mode: enable memory editing to freeze values." }
        val t = plan.target
        val fv = FrozenValue(ids.incrementAndGet(), t.pid, t.processName, t.address, t.type, plan.replacement.copyOf(), label)
        _frozen.update { list -> list.filterNot { it.pid == fv.pid && it.address == fv.address } + fv }
        _lastStopReason.value = null
        ensureRunning()
        return fv
    }

    fun unfreeze(id: Long) = _frozen.update { list -> list.filterNot { it.id == id } }

    fun isFrozen(pid: Int, address: Long): Boolean = _frozen.value.any { it.pid == pid && it.address == address }

    fun unfreeze(pid: Int, address: Long) = _frozen.update { list -> list.filterNot { it.pid == pid && it.address == address } }

    fun stopAll(reason: String) {
        if (_frozen.value.isNotEmpty()) _lastStopReason.value = reason
        _frozen.value = emptyList()
        job?.cancel()
        job = null
    }

    private fun ensureRunning() {
        if (job?.isActive == true) return
        job = scope.launch {
            var tick = 0L
            try {
                while (_frozen.value.isNotEmpty()) {
                    if (!policy().editingEnabled) { stopAll("memory editing was disabled"); break }
                    if (tick % processCheckEveryTicks == 0L && tick > 0) {
                        val alive = backend.getProcesses().map { it.pid }.toSet()
                        val gone = _frozen.value.filter { it.pid !in alive }
                        if (gone.isNotEmpty()) {
                            _lastStopReason.value = "process ${gone.first().processName} (pid ${gone.first().pid}) exited"
                            _frozen.update { list -> list.filter { it.pid in alive } }
                        }
                    }
                    for (fv in _frozen.value) {
                        val st = backend.writeMemory(fv.pid, fv.address, fv.bytes)
                        if (st is WriteStatus.Rejected) {
                            _lastStopReason.value = "write to ${fv.label} was rejected by the PS4"
                            unfreeze(fv.id)
                        }
                    }
                    tick++
                    delay(intervalMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendException) {
                _lastStopReason.value = "connection problem: ${e.message}"
                _frozen.value = emptyList()
            }
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 50L
        const val MAX_INTERVAL_MS = 10_000L
    }
}
