package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Hex
import com.ps4smartcheat.core.validate.SessionValidator
import com.ps4smartcheat.core.validate.Validity
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A saved code cave (cave part + jump part in one group) must get the same answer from the session check and from the
 * library ON/OFF switches: both resolve each part through its saved module reference and compare with the same code.
 *
 * The map used here is the case that made them disagree: the game image is mapped as `executable` (as on a PS4) and
 * there is also a mapping named after the process (`eboot.bin`) somewhere else. The switches used to resolve every
 * part through the process name and so looked at the wrong addresses ("does not match this game build") while the
 * session check, which uses the saved `executable+offset`, found both parts.
 */
class SavedCaveCheckTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockPs4DebugServer().also { it.faults.enableNg() }
    private val client = Ps4DebugClient()
    private val pid = 91
    private val image = 0x40_0000L
    private val other = 0x90_0000L

    private val caveOffset = 0xF010L
    private val hookOffset = 0x2006L
    private val caveOn = Hex.decode("B8E803000083E801890502110F00E9EC2FFFFF")
    private val caveOff = ByteArray(caveOn.size)
    private val hookOn = Hex.decode("E905D0000090909090")
    private val hookOff = Hex.decode("83E801890511E10F00")

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private fun game(): MockPs4DebugServer.Process {
        val p = server.addProcess(MockPs4DebugServer.Process(pid, "eboot.bin", titleId = "REAL00001", path = "/app0/eboot.bin"))
        p.region("executable", image, 0x10000, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_X)
        p.region("executable", image + 0x100000, 0x10000, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
        // Another mapping that carries the process name — not the game image.
        p.region("eboot.bin", other, 0x10000, MockPs4DebugServer.PROT_R)
        server.poke(pid, image + hookOffset, hookOff)
        return p
    }

    private fun connect() = runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000)) }

    private fun entries(module: String = "executable") = listOf(
        CheatEntry("c", "Coins · cave 1/1", ValueType.INT8, image + caveOffset, ModuleRef(module, caveOffset),
            originalBytes = Hex.encode(caveOff), patchBytes = Hex.encode(caveOn), notes = "Code cave of \"Coins\"", group = "Coins"),
        CheatEntry("j", "Coins · jump", ValueType.INT8, image + hookOffset, ModuleRef(module, hookOffset),
            originalBytes = Hex.encode(hookOff), patchBytes = Hex.encode(hookOn), group = "Coins"),
    )

    private fun profile(cheats: List<CheatEntry> = entries()) = CheatProfile("p", "REAL00001", "REAL00001", "01.00", "eboot.bin", cheats = cheats)

    private suspend fun check(p: CheatProfile = profile()): ModCheck {
        val f = CodeCheats.asGoldHenFile(p)!!
        return GoldHenImport.check(client, pid, f, f.mods.single(), client.getMemoryRegions(pid))
    }

    private suspend fun validate(p: CheatProfile = profile()) =
        SessionValidator.validate(client, pid, client.getMemoryRegions(pid), profile = p, processName = "eboot.bin").items

    private fun setOn(cave: Boolean, hook: Boolean) {
        server.poke(pid, image + caveOffset, if (cave) caveOn else caveOff)
        server.poke(pid, image + hookOffset, if (hook) hookOn else hookOff)
    }

    @Test fun switchesAndSessionCheckResolveTheSameAddresses() = runBlocking {
        game(); connect()
        val regions = client.getMemoryRegions(pid)
        // The root cause: the process name and the saved module do not give the same base on this map.
        assertEquals(other, ModuleResolver.baseOf("eboot.bin", regions))
        assertEquals(image, ModuleResolver.baseOf("executable", regions))
        setOn(cave = true, hook = true)

        val ck = check()
        assertEquals(listOf(image + caveOffset, image + hookOffset), ck.addresses)
        assertEquals(listOf(PatchState.ON, PatchState.ON), ck.states)
        assertEquals(PatchState.ON, ck.state)
        assertTrue(ck.canDisable)
        assertEquals(listOf("Coins · cave 1/1", "Coins · jump"), ck.parts.map { it.label })
        assertTrue(ck.parts[0].location, ck.parts[0].location.startsWith("executable+0xF010"))
        // Same answer from the session check.
        assertEquals(listOf(Validity.ACTIVE, Validity.ACTIVE), validate().map { it.validity })
        assertEquals(ck.addresses, validate().map { it.address })
    }

    @Test fun offPartialAndOnAreReportedPerPart() = runBlocking {
        game(); connect()
        // OFF: both original.
        var ck = check()
        assertEquals(listOf(PatchState.OFF, PatchState.OFF), ck.states)
        assertTrue(ck.canEnable && !ck.canDisable && !ck.partial)
        assertTrue(ck.parts.all { it.reason == null })
        assertEquals(listOf(0, 1), ck.writeOrder(true)) // cave first, jump last
        // Partial: the cave is written, the jump is not (interrupted enable).
        setOn(cave = true, hook = false)
        ck = check()
        assertEquals(listOf(PatchState.ON, PatchState.OFF), ck.states)
        assertTrue(ck.partial && ck.canEnable && ck.canDisable)
        assertNull(ck.state)
        assertEquals(listOf(1), ck.writeOrder(true)) // only the jump is missing
        assertEquals(listOf(0), ck.writeOrder(false))
        assertEquals(listOf(Validity.ACTIVE, Validity.VALID), validate().map { it.validity })
        // ON.
        setOn(cave = true, hook = true)
        ck = check()
        assertEquals(PatchState.ON, ck.state)
        assertEquals(listOf(1, 0), ck.writeOrder(false)) // jump first, cave last
    }

    @Test fun mismatchShowsWhichPartAndTheExpectedAndActualBytes() = runBlocking {
        game(); connect()
        setOn(cave = true, hook = true)
        val foreign = caveOn.copyOf().also { it[0] = 0x90.toByte() }
        server.poke(pid, image + caveOffset, foreign)
        val ck = check()
        assertEquals(listOf(PatchState.MISMATCH, PatchState.ON), ck.states)
        assertFalse(ck.canEnable)
        assertFalse(ck.canDisable) // never written over unknown bytes
        val cave = ck.parts[0]
        assertArrayEquals(foreign, cave.actual)
        assertArrayEquals(caveOn, cave.on)
        assertArrayEquals(caveOff, cave.off)
        assertTrue(cave.reason!!, cave.reason!!.contains("neither the saved ON nor the OFF"))
        assertNull(ck.parts[1].reason)
        assertEquals(Validity.INVALID, validate()[0].validity) // the session check agrees
    }

    @Test fun unloadedModuleIsUnmappedWithAReason() = runBlocking {
        game(); connect()
        // The game image is not in the map (e.g. the process restarted and the map is from before).
        val f = CodeCheats.asGoldHenFile(profile())!!
        val ck = GoldHenImport.check(client, pid, f, f.mods.single(), emptyList())
        assertEquals(listOf(PatchState.UNMAPPED, PatchState.UNMAPPED), ck.states)
        assertTrue(ck.parts.all { it.address == -1L && it.reason!!.contains("not loaded") })
    }

    @Test fun goldHenFilesUseTheGameImageNotAMappingNamedLikeTheProcess() = runBlocking {
        game(); connect()
        setOn(cave = false, hook = true)
        val json = """{"name":"T","id":"REAL00001","version":"01.00","process":"eboot.bin","mods":[{"name":"Jump","type":"checkbox",
            "memory":[{"offset":"2006","on":"${Hex.encode(hookOn)}","off":"${Hex.encode(hookOff)}"}]}]}"""
        val f = GoldHenImport.parse(json)
        val ck = GoldHenImport.check(client, pid, f, f.mods.single(), client.getMemoryRegions(pid))
        assertEquals(listOf(image + hookOffset), ck.addresses)
        assertEquals(PatchState.ON, ck.state)
    }

    @Test fun anInjectionSavedTwiceIsReportedAndReplacedOnSave() = runBlocking {
        val first = entries()
        val second = entries().map { it.copy(id = it.id + "2", patchBytes = if (it.id == "j") Hex.encode(hookOn) else Hex.encode(caveOn.copyOf().also { b -> b[0] = 0xB9.toByte() })) }
        // Both copies in one group cannot match memory at the same time: the mod says why instead of "mismatch".
        val twice = CodeCheats.asGoldHenFile(profile(first + second))!!.mods.single()
        assertFalse(twice.applicable)
        assertTrue(twice.problems.toString(), twice.problems.single().contains("saved twice"))

        // Saving again under the same name replaces the old parts in one write.
        val repo = CheatRepository(tmp.root.resolve("cheats.json"))
        repo.load()
        val p = repo.createProfileWithCheats("REAL00001", "REAL00001", "01.00", "eboot.bin", first)
        val old = InjectionEntries.previousParts(repo.profile(p.id), "Coins", null)
        assertEquals(listOf("c", "j"), old.map { it.id })
        repo.replaceCheats(p.id, old.map { it.id }.toSet(), second)
        val stored = CheatRepository(tmp.root.resolve("cheats.json")).also { it.load() }.profiles.value.single().cheats
        assertEquals(listOf("c2", "j2"), stored.map { it.id })
        assertTrue(CodeCheats.asGoldHenFile(stored.let { profile(it) })!!.mods.single().applicable)
        // Other cheats of the profile are not touched.
        assertTrue(InjectionEntries.previousParts(profile(first), "Other", null).isEmpty())
        assertNotEquals(first.map { it.id }, stored.map { it.id })
    }
}
