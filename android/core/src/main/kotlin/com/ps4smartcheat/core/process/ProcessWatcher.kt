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

/** Reads the process list and classifies every process (one CMD_PROC_INFO per process). */
suspend fun DebugBackend.listProcessDetails(): List<ProcessDetails> =
    getProcesses().distinctBy { it.pid }.sortedBy { it.pid }.map { e ->
        val info = try { getProcessInfo(e.pid) } catch (ex: BackendException) { null }
        GameDetector.classify(e, info)
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
        job = scope.launch {
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
