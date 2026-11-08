package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Address
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Addresses are written as "0x…" strings so files stay readable and 64-bit safe. */
object HexAddressSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("HexAddress", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(Address.format(value))
    override fun deserialize(decoder: Decoder): Long {
        val s = decoder.decodeString()
        return Address.parse(s) ?: throw kotlinx.serialization.SerializationException("Invalid address \"$s\"")
    }
}

/**
 * Address relative to a loaded module (e.g. `eboot.bin`). [offset] is measured from the lowest start
 * address of all map regions carrying [name] in the session where the cheat was saved.
 */
@Serializable
data class ModuleRef(
    val name: String,
    @Serializable(with = HexAddressSerializer::class) val offset: Long,
)

@Serializable
data class CheatEntry(
    val id: String,
    val name: String,
    val type: ValueType,
    /** Absolute address in the session where the cheat was found. Not guaranteed to stay valid. */
    @Serializable(with = HexAddressSerializer::class) val address: Long,
    val module: ModuleRef? = null,
    /** Value to write, as typed by the user (decimal/hex for ints, decimal for floats). */
    val replacementValue: String? = null,
    /** Original bytes (hex, memory order) observed before modification, if known. */
    val originalBytes: String? = null,
    val notes: String = "",
    /** pid of the process when discovered: used to warn about stale absolute addresses. */
    val discoveredPid: Int? = null,
    /**
     * Code/byte patch: the "on" bytes (hex). For a patch, [originalBytes] holds the "off" bytes and [type] /
     * [replacementValue] are not used. Toggled like a GoldHEN checkbox mod; never applied automatically.
     */
    val patchBytes: String? = null,
    /** Assembly source a code patch was built from (for display and editing). */
    val assembly: String? = null,
    /** Entries with the same group are toggled together and exported as one GoldHEN mod named after the group. */
    val group: String? = null,
    /** AOB signature of the original code (`??` = wildcard), used to find the patch again after a game update. */
    val signature: String? = null,
) {
    val isPatch: Boolean get() = !patchBytes.isNullOrBlank()
}

@Serializable
data class CheatProfile(
    val id: String,
    val name: String,
    /** Title ID such as CUSA12345, from CMD_PROC_INFO; null if unknown. */
    val titleId: String? = null,
    /** Game version (e.g. 01.05). Not reported by ps4debug: entered by the user. */
    val gameVersion: String? = null,
    val processName: String,
    val notes: String = "",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val cheats: List<CheatEntry> = emptyList(),
) {
    /** Profiles are isolated by (title, version, process). */
    val gameKey: String get() = "${titleId.orEmpty()}|${gameVersion.orEmpty()}|$processName"
}

@Serializable
data class CheatLibraryFile(
    val format: String = FORMAT_ID,
    @SerialName("formatVersion") val formatVersion: Int = FORMAT_VERSION,
    val profiles: List<CheatProfile> = emptyList(),
) {
    companion object {
        const val FORMAT_ID = "ps4-smartcheat-cheats"
        const val FORMAT_VERSION = 1
    }
}

object ModuleResolver {
    private val moduleSuffixes = listOf(".bin", ".elf", ".self", ".sprx", ".prx")

    fun isModuleName(name: String): Boolean = moduleSuffixes.any { name.endsWith(it, ignoreCase = true) }

    /** Lowest start address of all regions named [name], or null. */
    fun baseOf(name: String, regions: List<MemoryRegion>): Long? =
        regions.filter { it.name == name && it.isValid }.minOfOrNull { it.start }

    /** Computes a module-relative reference when [address] lies inside a module image. */
    fun refFor(address: Long, regions: List<MemoryRegion>): ModuleRef? {
        val region = regions.firstOrNull { it.contains(address) } ?: return null
        if (!isModuleName(region.name)) return null
        val base = baseOf(region.name, regions) ?: return null
        return ModuleRef(region.name, address - base)
    }

    fun resolve(ref: ModuleRef, regions: List<MemoryRegion>): Long? = baseOf(ref.name, regions)?.let { it + ref.offset }
}
