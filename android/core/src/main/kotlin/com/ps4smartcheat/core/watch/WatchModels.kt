package com.ps4smartcheat.core.watch

import com.ps4smartcheat.core.cheat.HexAddressSerializer
import com.ps4smartcheat.core.cheat.ModuleRef
import com.ps4smartcheat.core.scan.ValueType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One saved address in a game's address list ("watch list"). Nothing in here is ever written to the console
 * automatically: entries are only read, unless the user explicitly edits or freezes one.
 */
@Serializable
data class WatchEntry(
    val id: String,
    val label: String,
    val type: ValueType,
    /** Absolute address in the session where it was added. Only trusted for that pid, see [WatchResolver]. */
    @Serializable(with = HexAddressSerializer::class) val address: Long,
    /** Module-relative form (e.g. eboot.bin+0x120) when the address lies in a module image; survives restarts. */
    val module: ModuleRef? = null,
    /** pid of the process when added. */
    val discoveredPid: Int? = null,
    val notes: String = "",
    val createdAtMillis: Long = 0,
)

/** The address list of one game. Lists are keyed by title ID (or process name when no title ID is known). */
@Serializable
data class GameWatchList(
    val gameKey: String,
    val titleId: String? = null,
    val processName: String,
    val entries: List<WatchEntry> = emptyList(),
)

@Serializable
data class WatchListFile(
    val format: String = FORMAT_ID,
    @SerialName("formatVersion") val formatVersion: Int = FORMAT_VERSION,
    val games: List<GameWatchList> = emptyList(),
) {
    companion object {
        const val FORMAT_ID = "ps4-smartcheat-addresses"
        const val FORMAT_VERSION = 1
    }
}

object WatchKeys {
    /**
     * Title ID when known (CUSA12345 stays the same across restarts and pids); otherwise the process name, so an
     * unidentified process never shares a list with an identified game.
     */
    fun gameKey(titleId: String?, processName: String): String =
        titleId?.trim()?.takeIf { it.isNotEmpty() } ?: "process:$processName"
}
