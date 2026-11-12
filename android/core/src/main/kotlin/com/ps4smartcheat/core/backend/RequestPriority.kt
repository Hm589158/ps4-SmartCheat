package com.ps4smartcheat.core.backend

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks the requests made in a coroutine as background work (keep-alive, freeze, polling, live values). There is one
 * connection to the PS4 and requests take turns; a background request waits (up to [MAX_YIELD_MS]) while a user
 * action is waiting for the connection, so a tap is answered after at most the request already running, not after a
 * queue of polls:
 *
 * ```
 * withContext(BackgroundRequest) { backend.getProcesses() }
 * ```
 */
object BackgroundRequest : AbstractCoroutineContextElement(BackgroundRequestKey) {
    /** Longest a background request steps aside, so background work (a freeze) is never starved. */
    const val MAX_YIELD_MS = 2_000L
}

/** Context key of [BackgroundRequest]. */
object BackgroundRequestKey : CoroutineContext.Key<BackgroundRequest>
