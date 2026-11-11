package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety check before a GoldHEN export (and before a library switch). Offsets are relative to the game
 * executable. A good injection: hook at +0x2006 ("sub eax, 1 ; mov [rip+…], eax", 9 bytes) jumping to a cave at
 * +0xF010 that runs "mov eax, 1000", the two original instructions and "jmp return" (+0x200F).
 */
class GoldHenSafetyTest {
    private val hook = 0x2006L
    private val cave = 0xF010L
    private val hookOff = Hex.decode("83E801890511E10F00")
    private val hookOn = Hex.decode("E905D0000090909090")
    private val caveOn = Hex.decode("B8E803000083E801890502110F00E9EC2FFFFF")

    private fun injection(
        name: String = "Coins", caveBytes: ByteArray = caveOn, caveOff: ByteArray = ByteArray(caveBytes.size),
        on: ByteArray = hookOn, off: ByteArray = hookOff, hookAt: Long = hook, caveAt: Long = cave,
    ) = listOf(
        CheatEntry("$name-c", "$name · cave 1/1", ValueType.INT8, caveAt, ModuleRef("executable", caveAt),
            originalBytes = Hex.encode(caveOff), patchBytes = Hex.encode(caveBytes), notes = "Code cave of \"$name\"", group = name),
        CheatEntry("$name-j", "$name · jump", ValueType.INT8, hookAt, ModuleRef("executable", hookAt),
            originalBytes = Hex.encode(off), patchBytes = Hex.encode(on), assembly = "mov eax, 1000", group = name),
    )

    private fun profile(cheats: List<CheatEntry>) = CheatProfile("p", "Game", "CUSA12345", "01.00", "eboot.bin", cheats = cheats)

    private fun export(cheats: List<CheatEntry>) = GoldHenExporter.export(profile(cheats), "Game", listOf("me"))

    private fun skippedReason(cheats: List<CheatEntry>): String {
        val r = export(cheats)
        assertTrue("expected the mod to be refused: $r", r.skipped.isNotEmpty())
        return r.skipped.joinToString("\n")
    }

    @Test fun aCorrectInjectionIsExportedInTheGoldHenLayout() {
        val r = export(injection())
        assertTrue(r.errors.toString() + r.skipped, r.errors.isEmpty() && r.skipped.isEmpty())
        assertEquals(1, r.exported)
        val root = Json.parseToJsonElement(r.json!!).jsonObject
        assertEquals(listOf("name", "id", "version", "process", "mods", "credits"), root.keys.toList())
        val mod = root["mods"]!!.toString()
        assertTrue(mod, mod.contains("\"offset\":\"F010\"") && mod.contains("\"offset\":\"2006\"") && mod.contains("\"type\":\"checkbox\""))
        assertTrue(CodeCheats.asGoldHenFile(profile(injection()))!!.mods.single().applicable)
    }

    @Test fun offSavedWhileAnotherInjectionWasOnIsRefused() {
        // The hook was built while an older injection (cave at +0xF100) was ON: its jump was saved as "off".
        val oldJump = Hex.decode("E9F5D0000090909090")
        val reason = skippedReason(injection(off = oldJump))
        assertTrue(reason, reason.contains("OFF (the original code) starts with \"jmp"))
        // The library switches refuse it as well: writing "off" would jump into an empty cave.
        val mod = CodeCheats.asGoldHenFile(profile(injection(off = oldJump)))!!.mods.single()
        assertFalse(mod.applicable)
        assertTrue(mod.problems.toString(), mod.problems.any { it.startsWith("unsafe:") })
    }

    @Test fun offThatJumpsIntoACodeCaveIsRefused() {
        // A code patch at +0x3000 whose "off" is a jump into the Coins cave (saved while Coins was ON there).
        val patch = CheatEntry("x", "Speed", ValueType.INT8, 0x3000, ModuleRef("executable", 0x3000),
            originalBytes = "E90BC00000", patchBytes = "9090909090", assembly = "nop")
        val r = export(injection() + patch)
        assertEquals(1, r.exported) // Coins itself is fine
        assertTrue(r.skipped.toString(), r.skipped.single().contains("a jump into a code cave"))
    }

    @Test fun originalBytesCutInTheMiddleOfAnInstructionAreRefused() {
        val reason = skippedReason(injection(on = hookOn.copyOf(8), off = hookOff.copyOf(8)))
        assertTrue(reason, reason.contains("middle of an instruction"))
        val code = CheatEntry("x", "Cut", ValueType.INT8, 0x3000, ModuleRef("executable", 0x3000),
            originalBytes = "8905", patchBytes = "9090", assembly = "nop")
        assertTrue(skippedReason(listOf(code)).contains("OFF (original) ends in the middle of an instruction"))
    }

    @Test fun caveCodeMustReturnAndStayOutOfTheHook() {
        // No jump back: the CPU runs into the padding after the code.
        val noReturn = Hex.decode("B8E803000083E801")
        assertTrue(skippedReason(injection(caveBytes = noReturn)).contains("falls off the end of the cave"))
        // jmp to +0x2008: inside the bytes the hook's jump replaced.
        val intoHook = caveOn.copyOf().also { Hex.decode("E9E52FFFFF").copyInto(it, 14) }
        assertTrue(skippedReason(injection(caveBytes = intoHook)).contains("jumps into the replaced instructions"))
        // The hook must jump to this mod's cave.
        val elsewhere = Hex.decode("E9F5D0000090909090")
        assertTrue(skippedReason(injection(on = elsewhere)).contains("not a jump to the cave"))
    }

    @Test fun absoluteJumpsAndNonPaddingCavesAreRefused() {
        val abs = Hex.decode("B8E8030000FF2500000000") + ByteArray(8) { 0x40 }
        assertTrue(skippedReason(injection(caveBytes = abs)).contains("64-bit absolute jump"))
        assertTrue(skippedReason(injection(caveOff = ByteArray(caveOn.size) { 0x48 })).contains("not unused padding"))
        // A jump without its cave.
        val onlyJump = injection().filter { it.name.endsWith("jump") }
        assertTrue(export(onlyJump).let { it.skipped.isEmpty() && it.exported == 1 }) // alone it is an ordinary code patch…
        val withCaveNote = injection().map { if (it.name.endsWith("cave 1/1")) it.copy(group = "Other") else it }
        assertTrue(export(withCaveNote).skipped.isNotEmpty()) // …but a cave on its own is incomplete
    }

    @Test fun twoModsAtTheSameBytes() {
        fun value(id: String, original: String, value: String) = CheatEntry(id, "Coins $value", ValueType.INT32, 0x100, ModuleRef("executable", 0x100),
            replacementValue = value, originalBytes = original)
        // Same original bytes: alternatives (enable one), exported with a warning.
        val alt = export(listOf(value("a", "64000000", "999"), value("b", "64000000", "5000")))
        assertEquals(2, alt.exported)
        assertTrue(alt.warnings.toString(), alt.warnings.any { it.contains("enable only one") })
        // Different originals: one was saved while the other was ON.
        val bad = export(listOf(value("a", "64000000", "999"), value("b", "E7030000", "5000")))
        assertTrue(bad.errors.toString(), bad.json == null && bad.skipped.size == 2)
        assertTrue(bad.skipped.toString(), bad.skipped.all { it.contains("different original bytes") })
        // Two injections on the same hook never share code.
        val twice = export(injection() + injection(name = "Coins v2", caveAt = 0xF100, caveBytes = Hex.decode("B8E8030000E9052FFFFF"), on = Hex.decode("E9F5D0000090909090")))
        assertTrue(twice.skipped.toString(), twice.skipped.any { it.contains("two injections at the same code") })
    }

    @Test fun theFileReadsBackToTheSameBytes() {
        val r = export(injection())
        val file = Json.decodeFromString(GoldHenExporter.GhFile.serializer(), r.json!!)
        assertTrue(GoldHenExporter.formatProblems(r.json!!, file).isEmpty())
        val broken = file.copy(mods = file.mods.map { m -> m.copy(memory = m.memory.map { it.copy(offset = "0x" + it.offset) }) })
        assertTrue(GoldHenExporter.formatProblems(r.json!!, broken).any { it.contains("not hexadecimal") })
        assertNull(GoldHenImport.mismatch(GoldHenImport.parse(r.json!!), "CUSA12345", "eboot.bin"))
    }
}
