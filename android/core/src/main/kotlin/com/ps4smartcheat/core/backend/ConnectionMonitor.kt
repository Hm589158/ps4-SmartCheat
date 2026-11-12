package com.ps4smartcheat.core.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keep-alive: when the connection has been idle for [intervalMs], sends a harmless request so a link the
 * console or Wi-Fi dropped silently is noticed (and repaired by the client's auto-reconnect) before the
 * user's next action.
 */
class ConnectionMonitor(
    private val backend: DebugBackend,
    private val scope: CoroutineScope,
    @Volatile var intervalMs: Long = 10_000,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(com.ps4smartcheat.core.backend.BackgroundRequest) {
            while (isActive) {
                delay(intervalMs.coerceAtLeast(100))
                if (backend.state.value is ConnectionState.Connected && backend.idleMillis() >= intervalMs) {
                    try {
                        backend.ping()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // State already reflects the failure (Failed); nothing else to do here.
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
