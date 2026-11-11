package com.ps4smartcheat.core.tabs

import com.ps4smartcheat.core.scan.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabManagerTest {
    private var n = 0
    private val closed = mutableListOf<String>()
    private var saved: Pair<List<TabSpec>, String?>? = null

    private fun manager(initial: List<TabSpec> = emptyList(), active: String? = null) =
        TabManager(initial, active, newId = { "t${n++}" }, onChange = { l, a -> saved = l to a }, onClosed = { closed += it })

    private val TabManager.tools get() = tabs.value.map { it.tool }

    @Test fun startsWithTheFiveMainTools() {
        val m = manager()
        assertEquals(listOf(Tool.CONNECT, Tool.PROCESSES, Tool.SCANNER, Tool.ADDRESSES, Tool.CHEATS), m.tools)
        assertEquals(Tool.CONNECT, m.active.tool)
    }

    @Test fun restoresSavedTabsAndActiveTab() {
        val list = listOf(TabSpec("a", Tool.CODE, ToolArgs(address = 0x1000)), TabSpec("b", Tool.CHEATS))
        val m = manager(list, "b")
        assertEquals(list, m.tabs.value)
        assertEquals("b", m.activeId.value)
        // An unknown active id falls back to the first tab.
        assertEquals("a", manager(list, "gone").activeId.value)
    }

    @Test fun singleInstanceToolsAreFocusedNotDuplicated() {
        val m = manager()
        val scanner = m.tabs.value.first { it.tool == Tool.SCANNER }.id
        assertEquals(scanner, m.open(Tool.SCANNER))
        assertEquals(scanner, m.activeId.value)
        assertEquals(5, m.tabs.value.size)
        // Opening one that is not open adds it once.
        val help = m.open(Tool.HELP)
        assertEquals(help, m.open(Tool.HELP))
        assertEquals(1, m.tools.count { it == Tool.HELP })
    }

    @Test fun multiInstanceToolsOpenNextToTheActiveTabAndKeepTheirArgs() {
        val m = manager()
        m.focus(m.tabs.value[1].id) // Processes
        val a = m.open(Tool.CODE, ToolArgs(address = 0x400000))
        assertEquals(2, m.tabs.value.indexOfFirst { it.id == a })
        val b = m.open(Tool.CODE, ToolArgs(address = 0x500000))
        assertNotEquals(a, b)
        assertEquals(3, m.tabs.value.indexOfFirst { it.id == b })
        assertEquals(0x500000L, m.active.args.address)
        // The same tool at the same address is reused…
        assertEquals(a, m.open(Tool.CODE, ToolArgs(address = 0x400000)))
        // …unless a new tab is asked for (new-tab page, duplicate).
        val c = m.open(Tool.CODE, ToolArgs(address = 0x400000), reuse = false)
        assertTrue(c != a && c != b)
        assertEquals(3, m.tools.count { it == Tool.CODE })
    }

    @Test fun backgroundOpenDoesNotChangeTheActiveTab() {
        val m = manager()
        val before = m.activeId.value
        val id = m.open(Tool.EDITOR, ToolArgs(address = 0x10, type = ValueType.INT32), background = true)
        assertEquals(before, m.activeId.value)
        assertTrue(m.tabs.value.any { it.id == id })
    }

    @Test fun closingTheActiveTabReturnsToThePreviouslyUsedOneAndFreesItsState() {
        val m = manager()
        val cheats = m.tabs.value.last().id
        m.focus(cheats)
        val code = m.open(Tool.CODE, ToolArgs(address = 0x1234))
        m.close(code)
        assertEquals(cheats, m.activeId.value)
        assertEquals(listOf(code), closed)
        assertTrue(m.tabs.value.none { it.id == code })
        // Closing an inactive tab keeps the active one.
        val processes = m.tabs.value[1].id
        m.close(processes)
        assertEquals(cheats, m.activeId.value)
    }

    @Test fun closingTheLastTabLeavesAConnectTab() {
        val m = manager(listOf(TabSpec("only", Tool.HEX)))
        m.close("only")
        assertEquals(listOf(Tool.CONNECT), m.tools)
        assertEquals(m.tabs.value.single().id, m.activeId.value)
    }

    @Test fun closeOthersKeepsOneTab() {
        val m = manager()
        val scanner = m.tabs.value[2].id
        m.closeOthers(scanner)
        assertEquals(listOf(scanner), m.tabs.value.map { it.id })
        assertEquals(scanner, m.activeId.value)
        assertEquals(4, closed.size)
        assertFalse(m.back()) // nothing to go back to
    }

    @Test fun reorderingMovesTabsAndClampsAtTheEnds() {
        val m = manager()
        val ids = m.tabs.value.map { it.id }
        m.move(ids[0], 1)
        assertEquals(listOf(ids[1], ids[0], ids[2], ids[3], ids[4]), m.tabs.value.map { it.id })
        m.move(ids[0], -10)
        assertEquals(ids, m.tabs.value.map { it.id })
        m.moveTo(ids[4], 0)
        assertEquals(ids[4], m.tabs.value.first().id)
        m.move(ids[3], 99)
        assertEquals(ids[3], m.tabs.value.last().id)
        assertEquals(m.tabs.value, saved!!.first) // every change is persisted
    }

    @Test fun duplicateCopiesMultiInstanceTabsOnly() {
        val m = manager()
        val code = m.open(Tool.CODE, ToolArgs(address = 0x40))
        val copy = m.duplicate(code)!!
        assertNotEquals(code, copy)
        assertEquals(ToolArgs(address = 0x40), m.active.args)
        assertEquals(m.tabs.value.indexOfFirst { it.id == code } + 1, m.tabs.value.indexOfFirst { it.id == copy })
        assertNull(m.duplicate(m.tabs.value.first { it.tool == Tool.SCANNER }.id))
    }

    @Test fun backWalksTheTabHistory() {
        val m = manager()
        val (connect, processes, scanner) = m.tabs.value.map { it.id }
        m.focus(processes)
        m.focus(scanner)
        assertTrue(m.back())
        assertEquals(processes, m.activeId.value)
        assertTrue(m.back())
        assertEquals(connect, m.activeId.value)
        assertFalse(m.back())
        // A closed tab is skipped.
        m.focus(scanner)
        m.focus(processes)
        m.close(scanner)
        assertEquals(processes, m.activeId.value)
        assertTrue(m.back())
        assertEquals(connect, m.activeId.value)
        assertEquals(connect, saved!!.second)
    }

    @Test fun argsDescribeTheAddress() {
        assertTrue(ToolArgs().isEmpty)
        assertNull(ToolArgs().describe())
        assertEquals("0x000401000", ToolArgs(address = 0x401000).describe())
    }
}
