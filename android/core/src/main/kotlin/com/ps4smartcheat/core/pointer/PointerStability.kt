package com.ps4smartcheat.core.pointer

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.util.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A pointer-chain candidate kept across game sessions, with how often it held up.
 * [checks] counts sessions (pids) it was validated in; [passes] those in which it led to the right place.
 */
@Serializable
data class PointerCandidate(
    val chain: PointerChain,
    val checks: Int = 0,
    val passes: Int = 0,
    /** pids already counted, so re-validating in the same session does not inflate the score. */
    val sessions: List<Int> = emptyList(),
    val lastAddress: String? = null,
) {
    val ratio: Double get() = if (checks == 0) 0.0 else passes.toDouble() / checks

    /** Higher is better: survived sessions first, then shallow chains, then small offsets. */
    val score: Double get() = passes * 10.0 + ratio * 5.0 - chain.depth * 0.5 - chain.offsets.sumOf { it.coerceIn(0L, 65536L) } / 65536.0
}

/** Outcome of validating one candidate in the current session. */
enum class CandidateVerdict(val label: String) {
    /** Resolved to the address confirmed by the user (or the address most candidates agree on). */
    PASS("leads to the value"),
    /** Resolved, but somewhere else than the agreed address. */
    DISAGREES("resolves elsewhere"),
    BROKEN("broken in this session"),
}

data class CandidateCheck(val candidate: PointerCandidate, val verdict: CandidateVerdict, val address: Long?)

data class StabilityReport(
    val checks: List<CandidateCheck>,
    /** The address the result was compared with. */
    val reference: Long?,
    /** True when [reference] came from candidates agreeing (no address confirmed by the user). */
    val referenceIsConsensus: Boolean,
    val updated: List<PointerCandidate>,
) {
    val passed: Int get() = checks.count { it.verdict == CandidateVerdict.PASS }
}

/**
 * Validates saved pointer candidates in a new game session and ranks them by stability.
 *
 * When the user confirms where the value is now ([confirmed]), a candidate passes if it resolves there. Otherwise
 * the address that most resolving candidates agree on is used — correct chains converge on the same object while
 * accidental ones scatter — but only if at least two candidates agree; with no reference nothing is counted.
 */
object PointerStability {
    suspend fun validate(
        backend: DebugBackend,
        pid: Int,
        candidates: List<PointerCandidate>,
        regions: List<MemoryRegion>,
        valueSize: Int,
        confirmed: Long? = null,
    ): StabilityReport {
        val resolved = candidates.map { c ->
            c to when (val r = PointerResolver.resolve(backend, pid, c.chain, regions, valueSize)) {
                is PointerResolution.Resolved -> r.address
                is PointerResolution.Broken -> null
            }
        }
        val consensus = resolved.mapNotNull { it.second }.groupingBy { it }.eachCount()
            .filterValues { it >= 2 }.maxByOrNull { it.value }?.key
        val reference = confirmed ?: consensus
        val checks = resolved.map { (c, a) ->
            val verdict = when {
                a == null -> CandidateVerdict.BROKEN
                reference == null || a == reference -> CandidateVerdict.PASS
                else -> CandidateVerdict.DISAGREES
            }
            CandidateCheck(c, verdict, a)
        }
        val updated = checks.map { ch ->
            val c = ch.candidate
            if (reference == null && ch.verdict != CandidateVerdict.BROKEN) return@map c // nothing to compare with
            if (pid in c.sessions) return@map c.copy(lastAddress = ch.address?.let { "0x" + java.lang.Long.toHexString(it).uppercase() })
            c.copy(
                checks = c.checks + 1,
                passes = c.passes + if (ch.verdict == CandidateVerdict.PASS && reference != null) 1 else 0,
                sessions = (c.sessions + pid).takeLast(32),
                lastAddress = ch.address?.let { "0x" + java.lang.Long.toHexString(it).uppercase() },
            )
        }
        return StabilityReport(checks, reference, confirmed == null && reference != null, rank(updated))
    }

    fun rank(candidates: List<PointerCandidate>): List<PointerCandidate> =
        candidates.sortedWith(compareByDescending<PointerCandidate> { it.score }.thenBy { it.chain.describe() })

    /** Adds chains from a new scan; existing ones keep their history. */
    fun merge(existing: List<PointerCandidate>, chains: List<PointerChain>, limit: Int = MAX_CANDIDATES): List<PointerCandidate> {
        val known = existing.associateBy { it.chain }
        val added = chains.filter { it !in known }.map { PointerCandidate(it) }
        return rank(existing + added).take(limit)
    }

    const val MAX_CANDIDATES = 2000
}

/** Per-game storage of pointer candidates (JSON, atomic writes). */
class PointerCandidateStore(private val dir: File) {
    @Serializable
    private data class FileModel(val format: String = FORMAT, val version: Int = 1, val target: String = "", val candidates: List<PointerCandidate> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private fun fileFor(gameKey: String): File =
        File(dir, "ptr-" + gameKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80) + ".json")

    fun load(gameKey: String): Pair<String, List<PointerCandidate>> {
        val f = fileFor(gameKey)
        if (!f.exists()) return "" to emptyList()
        return try {
            val m = json.decodeFromString(FileModel.serializer(), f.readText())
            if (m.format != FORMAT) "" to emptyList() else m.target to m.candidates
        } catch (e: Exception) {
            "" to emptyList()
        }
    }

    /** [target] is a label for what the chains lead to (e.g. "Ammo, int32"). */
    fun save(gameKey: String, target: String, candidates: List<PointerCandidate>) {
        dir.mkdirs()
        AtomicFiles.writeText(fileFor(gameKey), json.encodeToString(FileModel.serializer(), FileModel(target = target, candidates = candidates)))
    }

    fun clear(gameKey: String) { fileFor(gameKey).delete() }

    companion object { const val FORMAT = "ps4-smartcheat-pointer-candidates" }
}
