package com.ps4smartcheat.core.process

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.logging.EventLevel
import com.ps4smartcheat.core.logging.EventSink
import com.ps4smartcheat.core.logging.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ProcessEvent {
    val identity: ProcessIdentity

    /** The watched process is gone (game closed or crashed). */
    data class Exited(override val identity: ProcessIdentity) : ProcessEvent

    /** The same program is running again under a new pid. */
    data class Restarted(override val identity: ProcessIdentity, val replacement: ProcessDetails) : ProcessEvent
}

/** Time limit for reading the details (CMD_PROC_INFO) of all processes in one listing. */
const val PROCESS_INFO_BUDGET_MS = 15_000L

/**
 * Reads the process list and classifies every process (one CMD_PROC_INFO per process).
 *
 * The listing can never hang the caller: the names are reported first ([onProgress]), then the details are read
 * with games and other programs first and system processes (named Sce…, recognised without details) last; reading
 * stops when [budgetMs] is used up or the connection is lost (a request that times out ends the session), and the
 * processes not read yet keep their name-only classification. A process whose details fail is shown without them.
 */
suspend fun DebugBackend.listProcessDetails(
    budgetMs: Long = PROCESS_INFO_BUDGET_MS,
    clock: () -> Long = System::currentTimeMillis,
    onProgress: ((List<ProcessDetails>) -> Unit)? = null,
): List<ProcessDetails> {
    val entries = getProcesses().distinctBy { it.pid }.sortedBy { it.pid }
    val out = entries.map { GameDetector.classify(it, null).copy(reason = "details not read yet") }.toMutableList()
    onProgress?.invoke(out.toList())
    val deadline = clock() + budgetMs
    val order = entries.indices.sortedBy { if (GameDetector.classify(entries[it], null).kind == ProcessKind.SYSTEM) 1 else 0 }
    for ((n, i) in order.withIndex()) {
        if (clock() > deadline) {
            order.drop(n).forEach { j -> out[j] = GameDetector.classify(entries[j], null).copy(reason = "details not read (time limit)") }
            break
        }
        val info = try {
            getProcessInfo(entries[i].pid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            if (state.value !is ConnectionState.Connected) {
                order.drop(n).forEach { j -> out[j] = GameDetector.classify(entries[j], null).copy(reason = "details not read (connection lost)") }
                break
            }
            null
        }
        out[i] = GameDetector.classify(entries[i], info)
        onProgress?.invoke(out.toList())
    }
    onProgress?.invoke(out.toList())
    return out
}

/**
 * Polls the process list while a process is selected and reports when it exits or restarts.
 * Detection is based on [ProcessIdentity] (pid + name + title ID + path), so a reused pid is not mistaken
 * for the old game.
 */
class ProcessWatcher(
    private val backend: DebugBackend,
    private val scope: CoroutineScope,
    @Volatile var intervalMs: Long = 3000,
    @Volatile var events: EventSink = EventSink.NONE,
) {
    private val _events = MutableSharedFlow<ProcessEvent>(extraBufferCapacity = 8)
    val processEvents: SharedFlow<ProcessEvent> = _events.asSharedFlow()

    @Volatile private var watched: ProcessIdentity? = null
    @Volatile private var exited = false
    private var job: Job? = null

    val current: ProcessIdentity? get() = watched

    fun watch(identity: ProcessIdentity?) {
        watched = identity
        exited = false
        if (identity == null) { stop(); return }
        if (job?.isActive == true) return
        job = scope.launch(com.ps4smartcheat.core.backend.BackgroundRequest) {
            while (isActive) {
                delay(intervalMs.coerceAtLeast(100))
                val target = watched ?: continue
                if (backend.state.value !is ConnectionState.Connected) continue
                try {
                    check(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackendException) {
                    // Connection problems are handled by the client; try again next tick.
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        watched = null
    }

    /** One detection pass (also usable directly from tests). */
    suspend fun check(target: ProcessIdentity) {
        val list = backend.getProcesses()
        val samePid = list.firstOrNull { it.pid == target.pid }
        val alive = samePid != null && samePid.name == target.name &&
            (target.titleId == null || runCatching { backend.getProcessInfo(target.pid).titleId.trim() }.getOrNull().let { it == null || it == target.titleId })
        if (alive) {
            exited = false
            return
        }
        if (!exited) {
            exited = true
            events.log(EventLevel.WARN, EventCategory.PROCESS, "process exited", "pid" to target.pid, "name" to target.name, "title" to target.titleId)
            _events.emit(ProcessEvent.Exited(target))
        }
        // Look for a new instance of the same program.
        val candidates = list.filter { it.name == target.name && it.pid != target.pid }
        for (c in candidates) {
            val info = try { backend.getProcessInfo(c.pid) } catch (e: BackendException) { null }
            val details = GameDetector.classify(c, info)
            if (details.identity.sameProgram(target)) {
                events.log(EventLevel.INFO, EventCategory.PROCESS, "process restarted", "oldPid" to target.pid, "newPid" to c.pid, "title" to details.titleId)
                watched = details.identity
                exited = false
                _events.emit(ProcessEvent.Restarted(target, details))
                return
            }
        }
    }
}
