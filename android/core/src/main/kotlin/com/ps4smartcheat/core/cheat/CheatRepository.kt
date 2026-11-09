package com.ps4smartcheat.core.cheat

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

data class ImportResult(val added: Int, val replaced: Int, val skipped: List<String>)

/**
 * Persistent cheat library stored as a single JSON file (the same format used for export).
 * Writes are atomic: data is written to a temp file and renamed over the old one.
 */
class CheatRepository(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val _profiles = MutableStateFlow<List<CheatProfile>>(emptyList())
    val profiles: StateFlow<List<CheatProfile>> = _profiles.asStateFlow()
    private val mutex = Mutex()
    private var loaded = false

    /** Non-null if the stored file could not be read; the broken file was preserved next to it. */
    @Volatile var loadError: String? = null
        private set

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        withContext(io) {
            loaded = true
            if (!file.exists()) { _profiles.value = emptyList(); return@withContext }
            try {
                _profiles.value = CheatJson.decode(file.readText()).profiles
                loadError = null
            } catch (e: Exception) {
                val backup = File(file.parentFile, file.name + ".corrupt-" + clock())
                file.copyTo(backup, overwrite = true)
                loadError = "Cheat library could not be read (${e.message}). A copy was saved as ${backup.name}."
                _profiles.value = emptyList()
            }
        }
    }

    fun newId(): String = idGenerator()

    fun profile(id: String): CheatProfile? = _profiles.value.firstOrNull { it.id == id }

    /** Profiles for one game (title/version/process); other games are never mixed in. */
    fun profilesForGame(titleId: String?, gameVersion: String?, processName: String): List<CheatProfile> =
        _profiles.value.filter {
            it.processName == processName && it.titleId == titleId && (gameVersion == null || it.gameVersion == gameVersion)
        }

    suspend fun createProfile(name: String, titleId: String?, gameVersion: String?, processName: String): CheatProfile {
        val now = clock()
        val p = CheatProfile(
            id = idGenerator(), name = name.trim(), titleId = titleId?.trim()?.ifBlank { null },
            gameVersion = gameVersion?.trim()?.ifBlank { null }, processName = processName,
            createdAtMillis = now, updatedAtMillis = now,
        )
        mutate { it + p }
        return p
    }

    suspend fun updateProfile(profile: CheatProfile) = mutate { list ->
        require(list.any { it.id == profile.id }) { "Unknown profile ${profile.id}" }
        list.map { if (it.id == profile.id) profile.copy(updatedAtMillis = clock()) else it }
    }

    suspend fun deleteProfile(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /**
     * Creates a profile that already contains [entry], in one atomic write: a failure leaves neither an empty profile
     * nor a half-saved cheat behind.
     */
    suspend fun createProfileWithCheat(name: String, titleId: String?, gameVersion: String?, processName: String, entry: CheatEntry): CheatProfile {
        require(name.isNotBlank()) { "Enter a profile name" }
        val now = clock()
        val p = CheatProfile(
            id = idGenerator(), name = name.trim(), titleId = titleId?.trim()?.ifBlank { null },
            gameVersion = gameVersion?.trim()?.ifBlank { null }, processName = processName,
            createdAtMillis = now, updatedAtMillis = now, cheats = listOf(entry),
        )
        mutate { it + p }
        return p
    }

    /**
     * Reads the library file back from storage and checks that [cheatId] is in profile [profileId] and that the whole
     * file still passes validation (a file that fails it would be set aside at the next start). Does not change state.
     */
    suspend fun verifyStored(profileId: String, cheatId: String): Boolean = mutex.withLock {
        withContext(io) {
            try {
                CheatJson.decode(file.readText()).profiles.firstOrNull { it.id == profileId }?.cheats?.any { it.id == cheatId } == true
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun upsertCheat(profileId: String, entry: CheatEntry) = mutate { list ->
        require(list.any { it.id == profileId }) { "Unknown profile $profileId" }
        list.map { p ->
            if (p.id != profileId) p
            else {
                val exists = p.cheats.any { it.id == entry.id }
                p.copy(
                    cheats = if (exists) p.cheats.map { if (it.id == entry.id) entry else it } else p.cheats + entry,
                    updatedAtMillis = clock(),
                )
            }
        }
    }

    suspend fun deleteCheat(profileId: String, cheatId: String) = mutate { list ->
        list.map { p -> if (p.id == profileId) p.copy(cheats = p.cheats.filterNot { it.id == cheatId }, updatedAtMillis = clock()) else p }
    }

    fun exportJson(profileIds: Collection<String>? = null): String {
        val sel = _profiles.value.filter { profileIds == null || it.id in profileIds }
        return CheatJson.encodeProfiles(sel)
    }

    /** Imports profiles. Same id → replaced; otherwise added. Invalid files throw [CheatFormatException]. */
    suspend fun importJson(text: String): ImportResult {
        val incoming = CheatJson.decode(text).profiles
        var added = 0
        var replaced = 0
        mutate { list ->
            val byId = list.associateBy { it.id }.toMutableMap()
            val order = list.map { it.id }.toMutableList()
            for (p in incoming) {
                if (byId.containsKey(p.id)) replaced++ else { added++; order += p.id }
                byId[p.id] = p
            }
            order.map { byId.getValue(it) }
        }
        return ImportResult(added, replaced, emptyList())
    }

    /**
     * Applies [transform] and persists the result before publishing it: if writing fails, the in-memory state is
     * unchanged and the exception reaches the caller. A save right after app start waits for the stored library to
     * be loaded first instead of overwriting it.
     */
    private suspend fun mutate(transform: (List<CheatProfile>) -> List<CheatProfile>) = mutex.withLock {
        if (!loaded) loadLocked()
        val next = transform(_profiles.value)
        CheatJson.validate(CheatLibraryFile(profiles = next)) // never write a file that would fail to load
        withContext(io) { persist(next) }
        _profiles.value = next
    }

    private fun persist(profiles: List<CheatProfile>) = AtomicFiles.writeText(file, CheatJson.encodeProfiles(profiles))
}
