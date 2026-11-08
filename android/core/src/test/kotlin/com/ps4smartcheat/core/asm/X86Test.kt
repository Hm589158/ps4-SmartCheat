package com.ps4smartcheat.core.asm

import com.ps4smartcheat.mockserver.DemoGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class X86Test {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun decodesTheDemoRoutineWithResolvedRipTargets() {
        val list = X86.decode(DemoGame.SPEND_CODE, DemoGame.SPEND_ROUTINE)
        assertEquals(listOf("mov", "sub", "mov", "ret"), list.map { it.mnemonic })
        assertEquals(listOf(6, 3, 6, 1), list.map { it.length })
        assertEquals("mov eax, [0x500120]", list[0].text)
        assertEquals("mov [0x500120], eax", list[2].text)
        assertEquals(DemoGame.COINS_ADDRESS, list[0].ripTarget)
        assertTrue(list[0].readsMemory && !list[0].writesMemory)
        assertTrue(list[2].writesMemory && !list[2].readsMemory)
        assertEquals(4, list[2].memory!!.size)
        assertNull(list[1].memory)
        assertEquals(Flow.RETURN, list[3].flow)
    }

    @Test fun branchesAndInvalidBytes() {
        // call rel32 to 0x1000 from 0x0F00; jne short +2; 0x06 is invalid in 64-bit mode
        val code = b(0xE8, 0xFB, 0x00, 0x00, 0x00, 0x75, 0x02, 0x06, 0x90)
        val list = X86.decode(code, 0x0F00)
        assertEquals(Flow.CALL, list[0].flow)
        assertEquals(0x1000L, list[0].branchTarget)
        assertEquals(Flow.CONDITIONAL_JUMP, list[1].flow)
        assertEquals(0x0F09L, list[1].branchTarget)
        assertFalse(list[2].valid)
        assertEquals("db 0x06", list[2].text)
        assertEquals("nop", list[3].text)
    }

    @Test fun registerBasedOperandAndEffectiveAddress() {
        val ins = X86.decodeOne(b(0x89, 0x43, 0x1C), 0, 0x1000) // mov [rbx+0x1C], eax
        assertEquals("mov [rbx+0x1C], eax", ins.text)
        assertEquals("rbx", ins.memory!!.base)
        assertEquals(0x1CL, ins.memory!!.displacement)
        assertEquals(0x2000_001CL, X86.effectiveAddress(ins, mapOf("rbx" to 0x2000_0000L)))
        assertNull(X86.effectiveAddress(ins, emptyMap()))
        // 32-bit base register resolved from its 64-bit parent: mov eax, [ecx+8] (67 8B 41 08)
        val ins32 = X86.decodeOne(b(0x67, 0x8B, 0x41, 0x08), 0, 0)
        assertEquals(0x10L, X86.effectiveAddress(ins32, mapOf("rcx" to 0xFFFF_FFFF_0000_0008uL.toLong())))
    }

    @Test fun findsTheInstructionBeforeATrapRip() {
        // A data watchpoint traps after the store: RIP = 0x40200F. Window = 30 bytes before it.
        val base = DemoGame.SPEND_ROUTINE
        val window = ByteArray(14) { 0x90.toByte() } + DemoGame.SPEND_CODE.copyOf(15)
        val windowStart = base - 14
        val ins = X86.instructionEndingAt(window, windowStart, DemoGame.STORE_COINS + 6, DemoGame.COINS_ADDRESS until DemoGame.COINS_ADDRESS + 4)
        assertNotNull(ins)
        assertEquals(DemoGame.STORE_COINS, ins!!.address)
        assertTrue(ins.writesMemory)
    }

    @Test fun syncStartAvoidsStartingMidInstruction() {
        val code = ByteArray(8) { 0x90.toByte() } + DemoGame.SPEND_CODE
        // Show instructions before SUB_ONE: decoding from the window start must land on it.
        val start = X86.syncStart(code, DemoGame.SPEND_ROUTINE - 8, DemoGame.SUB_ONE)
        assertEquals(DemoGame.SPEND_ROUTINE - 8, start)
        val list = X86.decode(code, start!!)
        assertTrue(list.any { it.address == DemoGame.SUB_ONE })
    }

    @Test fun signatureWildcardsDisplacements() {
        val list = X86.decode(DemoGame.SPEND_CODE, DemoGame.SPEND_ROUTINE)
        assertEquals("8B 05 ?? ?? ?? ?? 83 E8 01 89 05 ?? ?? ?? ?? C3", X86.signature(list))
    }

    @Test fun normalisesSource() {
        assertEquals("nop\nmov eax, 5\nret", CodeTools.normalise("  nop ; mov   eax,  5 // comment\n\n# only comment\nret"))
    }
}
