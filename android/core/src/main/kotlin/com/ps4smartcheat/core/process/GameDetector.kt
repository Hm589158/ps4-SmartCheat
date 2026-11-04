package com.ps4smartcheat.core.process

import com.ps4smartcheat.core.model.ProcessEntry
import com.ps4smartcheat.core.model.ProcessInfo

enum class ProcessKind(val label: String) { GAME("Game"), SYSTEM("System"), OTHER("Other") }

/** A process with its CMD_PROC_INFO data and classification. */
data class ProcessDetails(
    val entry: ProcessEntry,
    val info: ProcessInfo?,
    val kind: ProcessKind,
    /** Human-readable reason for [kind] (shown in the UI and logs). */
    val reason: String,
) {
    val pid: Int get() = entry.pid
    val name: String get() = entry.name
    val titleId: String? get() = info?.titleId?.trim()?.ifBlank { null }
    val path: String? get() = info?.path?.trim()?.ifBlank { null }
    val identity: ProcessIdentity get() = ProcessIdentity(pid, name, titleId, path)
}

/**
 * Stable identity of a running process. A pid alone is not enough (pids are reused); a name alone is not
 * enough (games use eboot.bin, default.elf, default_mp.elf, …).
 */
data class ProcessIdentity(val pid: Int, val name: String, val titleId: String?, val path: String?) {
    /** Same program, possibly a new instance (restart) with a different pid. */
    fun sameProgram(other: ProcessIdentity): Boolean = when {
        titleId != null && other.titleId != null -> titleId == other.titleId && name == other.name
        path != null && other.path != null -> path == other.path
        else -> name == other.name
    }
}

/**
 * Heuristic game detection from process name, title ID and executable path.
 *
 * Assumptions (documented, not verified on every title): PS4 games and homebrew carry a title ID of four
 * letters + five digits (CUSA12345, homebrew e.g. LAPY10010); system applications use NPXSxxxxx; system
 * daemons are named Sce* or are well-known ELF names.
 */
object GameDetector {
    private val TITLE_ID = Regex("^[A-Z]{4}\\d{5}$")
    private val SYSTEM_NAMES = setOf(
        "mini-syscore.elf", "syscore.elf", "orbis_audiod.elf", "GnmCompositor.elf", "fs_cleaner.elf", "kernel",
    )

    fun classify(entry: ProcessEntry, info: ProcessInfo?): ProcessDetails {
        val name = entry.name
        val titleId = info?.titleId?.trim().orEmpty()
        val path = info?.path?.trim().orEmpty()
        val (kind, reason) = when {
            name.startsWith("Sce") || name in SYSTEM_NAMES -> ProcessKind.SYSTEM to "system process name"
            titleId.startsWith("NPXS") -> ProcessKind.SYSTEM to "system application $titleId"
            TITLE_ID.matches(titleId) -> ProcessKind.GAME to "title ID $titleId"
            (path.contains("/app0/") || path.contains("/pfsmnt/")) && isExecutableName(name) ->
                ProcessKind.GAME to "executable under the application directory"
            else -> ProcessKind.OTHER to if (info == null) "no process info" else "no title ID"
        }
        return ProcessDetails(entry, info, kind, reason)
    }

    private fun isExecutableName(n: String) = n.endsWith(".bin") || n.endsWith(".elf") || n.endsWith(".self")

    /**
     * The most likely running game, or null when there is none or the choice is ambiguous (several games
     * with different title IDs).
     */
    fun pickGame(processes: List<ProcessDetails>): ProcessDetails? {
        val games = processes.filter { it.kind == ProcessKind.GAME }
        if (games.isEmpty()) return null
        val titles = games.mapNotNull { it.titleId }.toSet()
        if (titles.size > 1) return null
        // Prefer the main executable when one title has several processes.
        return games.sortedWith(
            compareByDescending<ProcessDetails> { it.name == "eboot.bin" }
                .thenByDescending { it.path?.contains("/app0/") == true }
                .thenBy { it.pid },
        ).first()
    }
}
