package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.cheat.GoldHenExporter.VersionCheck
import com.ps4smartcheat.core.scan.ValueType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File naming and version format of GoldHEN exports. Rules from the official GoldHEN_Cheat_Repository README
 * ("Storage"): `{titleid}_{version}.json` for eboot.bin, `{titleid}_{version}_{process}.json` otherwise — e.g. the
 * repository's own `CUSA00018_01.21_default.elf.json` with `"process": "default.elf"`.
 */
class GoldHenExportNamingTest {
    private fun cheat(id: String, offset: Long, module: String = "default.elf") =
        CheatEntry(id, "Ammo $id", ValueType.INT32, 0x400000 + offset, ModuleRef(module, offset), "99", "05000000")

    private fun profile(process: String, version: String?, title: String? = "CUSA05294") =
        CheatProfile("p", "My game name", title, version, process, cheats = listOf(cheat("a", 0x1000, if (process == "eboot.bin") "eboot.bin" else "executable")))

    @Test fun fileNameFollowsTheProcess() {
        assertEquals("CUSA05294_01.25.json", GoldHenExporter.fileName("CUSA05294", "01.25", "eboot.bin"))
        assertEquals("CUSA05294_01.25_default.elf.json", GoldHenExporter.fileName("CUSA05294", "01.25", "default.elf"))
        assertEquals("CUSA00018_01.21_default_mp.elf.json", GoldHenExporter.fileName("CUSA00018", "01.21", "default_mp.elf"))
        // The game name never takes part; title ID and version are normalised.
        assertEquals("CUSA05294_01.25_default.elf.json", GoldHenExporter.fileName(profile("default.elf", "1.25", "cusa-05294")))
        assertEquals("CUSA05294_01.25.json", GoldHenExporter.fileName(profile("eboot.bin", "01.25")))
        assertNull(GoldHenExporter.fileName(profile("default.elf", "1.5")))
        assertNull(GoldHenExporter.fileName(profile("a/b.elf", "01.25")))
    }

    @Test fun versionsAreBroughtIntoGoldHenForm() {
        assertEquals(VersionCheck.Ok("01.25", true), GoldHenExporter.normalizeVersion("1.25"))
        assertEquals(VersionCheck.Ok("01.25", false), GoldHenExporter.normalizeVersion("01.25"))
        assertEquals(VersionCheck.Ok("01.25", true), GoldHenExporter.normalizeVersion(" v1,25 "))
        assertEquals(VersionCheck.Ok("02.00", true), GoldHenExporter.normalizeVersion("2"))
        assertEquals(VersionCheck.Ok("01.00", true), GoldHenExporter.normalizeVersion("1.0"))
        assertEquals(VersionCheck.Ok("10.50", false), GoldHenExporter.normalizeVersion("10.50"))
        // Ambiguous: never guessed, both forms offered.
        val amb = GoldHenExporter.normalizeVersion("1.5") as VersionCheck.Invalid
        assertEquals(listOf("01.05", "01.50"), amb.suggestions)
        for (bad in listOf("", "  ", "1.250", "abc", "100.00", "1.2.3")) assertTrue(bad, GoldHenExporter.normalizeVersion(bad) is VersionCheck.Invalid)
        assertEquals("CUSA05294", GoldHenExporter.normalizeTitleId(" cusa05294 "))
        assertNull(GoldHenExporter.normalizeTitleId("CUSA0529"))
    }

    @Test fun exportUsesTheNormalisedValuesAndTheTargetProcess() {
        val r = GoldHenExporter.export(profile("default.elf", "1.25"), "Game", emptyList())
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val root = Json.parseToJsonElement(r.json!!).jsonObject
        assertEquals("01.25", root["version"]!!.jsonPrimitive.content)
        assertEquals("default.elf", root["process"]!!.jsonPrimitive.content)
        assertEquals("CUSA05294", root["id"]!!.jsonPrimitive.content)
        // The cheat saved under the map name "executable" belongs to default.elf's image.
        assertEquals(1, r.exported)
        // Imported back, the file is for that process.
        assertEquals("default.elf", GoldHenImport.parse(r.json!!).process)
    }

    @Test fun previewShowsNameProblemsAndWhatIsExported() {
        val p = profile("default.elf", null)
        val ok = GoldHenExporter.preview(p, "CUSA05294", "1.25", "Game")
        assertTrue(ok.problems.toString(), ok.ok)
        assertEquals("CUSA05294_01.25_default.elf.json", ok.fileName)
        assertEquals("/user/data/GoldHEN/cheats/json/CUSA05294_01.25_default.elf.json", ok.uploadPath)
        assertEquals(1, ok.exportable)

        val amb = GoldHenExporter.preview(p, "CUSA05294", "1.5", "Game")
        assertFalse(amb.ok)
        assertNull(amb.fileName)
        assertEquals(listOf("01.05", "01.50"), (amb.version as VersionCheck.Invalid).suggestions)

        val wrongGame = GoldHenExporter.preview(p, "CUSA05294", "01.25", "Game", runningTitleId = "CUSA00001")
        assertFalse(wrongGame.ok)
        assertTrue(wrongGame.problems.single().contains("running game"))

        val nothing = GoldHenExporter.preview(p.copy(cheats = listOf(cheat("h", 0, "libkernel.sprx"))), "CUSA05294", "01.25", "Game")
        assertFalse(nothing.ok)
        assertEquals(1, nothing.skipped.size)
    }
}
