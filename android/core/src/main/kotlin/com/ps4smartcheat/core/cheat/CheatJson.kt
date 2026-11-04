package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.scan.ValueParseException
import com.ps4smartcheat.core.util.Hex
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CheatFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Encoding/decoding and validation of the application's own cheat JSON format. */
object CheatJson {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(file: CheatLibraryFile): String = json.encodeToString(CheatLibraryFile.serializer(), file)

    fun encodeProfiles(profiles: List<CheatProfile>): String = encode(CheatLibraryFile(profiles = profiles))

    fun decode(text: String): CheatLibraryFile {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw CheatFormatException("Not a JSON object: ${e.message}", e)
        }
        val format = root["format"]?.jsonPrimitive?.content
        if (format != CheatLibraryFile.FORMAT_ID) {
            throw CheatFormatException(
                if (root.containsKey("mods")) "This looks like a GoldHEN cheat file; import of GoldHEN files is not supported."
                else "Unknown file format \"${format ?: "missing"}\"",
            )
        }
        val version = runCatching { root["formatVersion"]?.jsonPrimitive?.int }.getOrNull()
            ?: throw CheatFormatException("Missing formatVersion")
        if (version > CheatLibraryFile.FORMAT_VERSION) {
            throw CheatFormatException("File format version $version is newer than supported (${CheatLibraryFile.FORMAT_VERSION}). Update the app.")
        }
        if (version < 1) throw CheatFormatException("Invalid formatVersion $version")
        val file = try {
            json.decodeFromString(CheatLibraryFile.serializer(), text)
        } catch (e: SerializationException) {
            throw CheatFormatException("Invalid cheat file: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw CheatFormatException("Invalid cheat file: ${e.message}", e)
        }
        validate(file)
        return file
    }

    /** Structural validation of every profile and entry. */
    fun validate(file: CheatLibraryFile) {
        val ids = HashSet<String>()
        for (p in file.profiles) {
            if (p.id.isBlank()) throw CheatFormatException("Profile without id")
            if (!ids.add(p.id)) throw CheatFormatException("Duplicate profile id ${p.id}")
            if (p.name.isBlank()) throw CheatFormatException("Profile ${p.id} has no name")
            if (p.processName.isBlank()) throw CheatFormatException("Profile ${p.name} has no process name")
            val cheatIds = HashSet<String>()
            for (c in p.cheats) {
                if (c.id.isBlank() || !cheatIds.add(c.id)) throw CheatFormatException("Invalid or duplicate cheat id in ${p.name}")
                if (c.name.isBlank()) throw CheatFormatException("Cheat ${c.id} in ${p.name} has no name")
                c.replacementValue?.let {
                    try { ValueCodec.parse(c.type, it) } catch (e: ValueParseException) {
                        throw CheatFormatException("Cheat \"${c.name}\": ${e.message}")
                    }
                }
                c.originalBytes?.let {
                    val b = try { Hex.decode(it) } catch (e: IllegalArgumentException) {
                        throw CheatFormatException("Cheat \"${c.name}\": invalid originalBytes")
                    }
                    if (b.size != c.type.size) throw CheatFormatException("Cheat \"${c.name}\": originalBytes length does not match ${c.type.label}")
                }
            }
        }
    }
}
