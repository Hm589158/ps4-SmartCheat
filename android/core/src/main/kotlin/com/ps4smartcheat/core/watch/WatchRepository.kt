package com.ps4smartcheat.core.watch

import com.ps4smartcheat.core.cheat.CheatJson
import com.ps4smartcheat.core.util.AtomicFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Result of [WatchRepository.add]: the stored entry and whether it was already in the list. */
data class AddResult(val entry: WatchEntry, val alreadyPresent: Boolean)

/** Persistent per-game address lists in one JSON file, written atomically. */
class WatchRepository(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val _games = MutableStateFlow<List<GameWatchList>>(emptyList())
    val games: StateFlow<List<GameWatchList>> = _games.asStateFlow()
    private val mutex = Mutex()
    private var loaded = false

    /** Non-null if the stored file could not be read; the broken file was preserved next to it. */
    @Volatile var loadError: String? = null
        private set

    suspend fun load() = mutex.withLock {
        withContext(io) {
            loaded = true
            if (!file.exists()) { _games.value = emptyList(); return@withContext }
            try {
                val decoded = CheatJson.json.decodeFromString(WatchListFile.serializer(), file.readText())
                require(decoded.format == WatchListFile.FORMAT_ID) { "unknown format ${decoded.format}" }
                require(decoded.formatVersion <= WatchListFile.FORMAT_VERSION) { "file is from a newer app version" }
                _games.value = decoded.games
                loadError = null
            } catch (e: Exception) {
                val backup = File(file.parentFile, file.name + ".corrupt-" + clock())
                file.copyTo(backup, overwrite = true)
                loadError = "Address list could not be read (${e.message}). A copy was saved as ${backup.name}."
                _games.value = emptyList()
            }
        }
    }

    fun list(gameKey: String): GameWatchList? = _games.value.firstOrNull { it.gameKey == gameKey }

    fun entries(gameKey: String): List<WatchEntry> = list(gameKey)?.entries.orEmpty()

    /**
     * Adds [entry] (its id and timestamp are assigned here) to the game's list. An entry for the same location
     * and type is not duplicated: the existing one is returned with [AddResult.alreadyPresent].
     */
    suspend fun add(titleId: String?, processName: String, entry: WatchEntry): AddResult {
        val key = WatchKeys.gameKey(titleId, processName)
        var result: AddResult? = null
        mutate { games ->
            val game = games.firstOrNull { it.gameKey == key }
                ?: GameWatchList(key, titleId?.trim()?.ifEmpty { null }, processName)
            val existing = game.entries.firstOrNull { sameLocation(it, entry) }
            if (existing != null) {
                result = AddResult(existing, true)
                return@mutate games
            }
            val stored = entry.copy(id = idGenerator(), label = entry.label.trim(), createdAtMillis = clock())
            result = AddResult(stored, false)
            val updated = game.copy(entries = game.entries + stored)
            if (games.any { it.gameKey == key }) games.map { if (it.gameKey == key) updated else it } else games + updated
        }
        return result!!
    }

    suspend fun update(gameKey: String, entry: WatchEntry) = mutate { games ->
        games.map { g ->
            if (g.gameKey != gameKey) g
            else {
                require(g.entries.any { it.id == entry.id }) { "Unknown entry ${entry.id}" }
                g.copy(entries = g.entries.map { if (it.id == entry.id) entry.copy(label = entry.label.trim()) else it })
            }
        }
    }

    suspend fun remove(gameKey: String, entryId: String) = mutate { games ->
        games.map { g -> if (g.gameKey == gameKey) g.copy(entries = g.entries.filterNot { it.id == entryId }) else g }
            .filter { it.entries.isNotEmpty() }
    }

    suspend fun clear(gameKey: String) = mutate { games -> games.filterNot { it.gameKey == gameKey } }

    private fun sameLocation(a: WatchEntry, b: WatchEntry): Boolean {
        if (a.type != b.type) return false
        if (a.pointer != null || b.pointer != null) return a.pointer == b.pointer
        val am = a.module
        val bm = b.module
        return if (am != null && bm != null) am == bm else a.address == b.address && a.discoveredPid == b.discoveredPid
    }

    private suspend fun mutate(transform: (List<GameWatchList>) -> List<GameWatchList>) = mutex.withLock {
        check(loaded) { "WatchRepository.load() must be called first" }
        val next = transform(_games.value)
        if (next != _games.value) {
            withContext(io) { AtomicFiles.writeText(file, CheatJson.json.encodeToString(WatchListFile.serializer(), WatchListFile(games = next))) }
            _games.value = next
        }
    }
}
