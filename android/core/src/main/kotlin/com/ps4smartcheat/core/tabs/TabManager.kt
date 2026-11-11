package com.ps4smartcheat.core.tabs

import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The tools that can be opened in a tab. [singleInstance] tools show shared, app-wide state (the connection, the
 * scan session, the cheat library…): opening one again focuses its tab. The others can be opened any number of times,
 * each tab with its own state (e.g. two disassemblers at different addresses).
 */
enum class Tool(val label: String, val title: String, val singleInstance: Boolean) {
    CONNECT("Connect", "Connection", true),
    PROCESSES("Processes", "Running processes", true),
    SCANNER("Scanner", "Memory scanner", true),
    ADDRESSES("Addresses", "Address list", true),
    CHEATS("Cheats", "Saved cheats", true),
    EDITOR("Editor", "Memory editor", false),
    HEX("Memory", "Memory viewer", false),
    CODE("Code", "Disassembler", false),
    INJECT("Code cave", "Code injection", false),
    WATCH("Watch", "Watchpoint", false),
    DEBUGGER("Debugger", "Debugger", false),
    POINTER("Pointers", "Pointer scan", false),
    PATTERN("Pattern", "Pattern search", true),
    SETTINGS("Settings", "Settings", true),
    DIAGNOSTICS("Diagnostics", "Diagnostics", true),
    HELP("Guide", "Guide", true),
}

/** What a tool was opened with (e.g. the address to show). All optional. */
data class ToolArgs(
    val address: Long? = null,
    val type: ValueType? = null,
    val size: Int? = null,
    val writes: Boolean? = null,
) {
    val isEmpty: Boolean get() = address == null && type == null && size == null && writes == null

    /** Short text for a tab: the address, if any. */
    fun describe(): String? = address?.let { Address.format(it) }
}

/** One open tab. [id] is stable for the tab's lifetime (it keys the tab's saved state and view models). */
data class TabSpec(val id: String, val tool: Tool, val args: ToolArgs = ToolArgs())

/**
 * Central window management: the open tabs, their order, the active tab and the order in which tabs were used.
 * All tools share the app's single container (one PS4 connection, one selected process);
 * each tab gets its own view models, so switching tabs never loses a tool's work.
 *
 * Pure Kotlin (no Android types) so it is unit-tested in :core; [onChange] persists the tab list.
 */
class TabManager(
    initial: List<TabSpec> = emptyList(),
    initialActive: String? = null,
    private val newId: () -> String = defaultIds(),
    private val onChange: (List<TabSpec>, String?) -> Unit = { _, _ -> },
    /** Called when a tab is closed, to free its state. */
    private val onClosed: (String) -> Unit = {},
) {
    private val _tabs = MutableStateFlow(initial.ifEmpty { defaultTabs(newId) })
    val tabs: StateFlow<List<TabSpec>> = _tabs.asStateFlow()
    private val _active = MutableStateFlow(initialActive?.takeIf { id -> _tabs.value.any { it.id == id } } ?: _tabs.value.first().id)
    val activeId: StateFlow<String> = _active.asStateFlow()
    /** Recently used tabs, most recent last (the active one included). Back returns along it. */
    private val history = ArrayDeque<String>().apply { add(_active.value) }

    val active: TabSpec get() = _tabs.value.first { it.id == _active.value }

    /**
     * Opens [tool]. A single-instance tool already open is focused (its args are ignored). A multi-instance tool
     * already open with the same args is focused too when [reuse] (opening the same address twice does not pile up
     * tabs); otherwise a new tab is inserted right after the active one, like a link opened from the current tab.
     * Returns the tab's id.
     */
    fun open(tool: Tool, args: ToolArgs = ToolArgs(), background: Boolean = false, reuse: Boolean = true): String {
        val existing = when {
            tool.singleInstance -> _tabs.value.firstOrNull { it.tool == tool }
            reuse -> _tabs.value.firstOrNull { it.tool == tool && it.args == args }
            else -> null
        }
        existing?.let { if (!background) focus(it.id); return it.id }
        val spec = TabSpec(newId(), tool, args)
        val list = _tabs.value.toMutableList()
        val at = list.indexOfFirst { it.id == _active.value }.let { if (it < 0) list.size else it + 1 }
        list.add(at, spec)
        _tabs.value = list
        if (!background) focus(spec.id) else changed()
        return spec.id
    }

    fun focus(id: String) {
        if (_tabs.value.none { it.id == id }) return
        _active.value = id
        history.remove(id)
        history.addLast(id)
        while (history.size > MAX_HISTORY) history.removeFirst()
        changed()
    }

    /**
     * Closes a tab and frees its state. The tab used before it becomes active (or its neighbour). Closing the last tab
     * opens a Connect tab, so there is always one.
     */
    fun close(id: String) {
        val list = _tabs.value
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val rest = list.filterNot { it.id == id }
        history.remove(id)
        onClosed(id)
        if (rest.isEmpty()) {
            val spec = TabSpec(newId(), Tool.CONNECT)
            _tabs.value = listOf(spec)
            history.clear()
            focus(spec.id)
            return
        }
        _tabs.value = rest
        if (_active.value == id) {
            val previous = history.lastOrNull { h -> rest.any { it.id == h } }
            focus(previous ?: rest[minOf(index, rest.size - 1)].id)
        } else {
            changed()
        }
    }

    /** Closes every tab except [id]. */
    fun closeOthers(id: String) {
        if (_tabs.value.none { it.id == id }) return
        _tabs.value.filter { it.id != id }.forEach { onClosed(it.id); history.remove(it.id) }
        _tabs.value = _tabs.value.filter { it.id == id }
        focus(id)
    }

    /** Moves a tab [delta] places (negative = left), clamped to the ends. */
    fun move(id: String, delta: Int) {
        val list = _tabs.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        moveTo(id, (from + delta).coerceIn(0, list.size - 1))
    }

    /** Moves a tab to position [index]. */
    fun moveTo(id: String, index: Int) {
        val list = _tabs.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = index.coerceIn(0, list.size - 1)
        if (from == to) return
        val spec = list.removeAt(from)
        list.add(to, spec)
        _tabs.value = list
        changed()
    }

    /** Opens a copy of a multi-instance tab (same tool and args, fresh state) next to it. */
    fun duplicate(id: String): String? {
        val spec = _tabs.value.firstOrNull { it.id == id } ?: return null
        if (spec.tool.singleInstance) return null
        focus(id)
        return open(spec.tool, spec.args, reuse = false)
    }

    /** Back: returns to the tab used before the active one. False when there is none (the app may close). */
    fun back(): Boolean {
        if (history.size < 2) return false
        history.removeLast()
        val previous = history.lastOrNull { h -> _tabs.value.any { it.id == h } } ?: return false
        _active.value = previous
        changed()
        return true
    }

    private fun changed() = onChange(_tabs.value, _active.value)

    companion object {
        const val MAX_HISTORY = 50

        /** The tabs a fresh install starts with: the five main tools of the former bottom bar. */
        fun defaultTabs(newId: () -> String): List<TabSpec> =
            listOf(Tool.CONNECT, Tool.PROCESSES, Tool.SCANNER, Tool.ADDRESSES, Tool.CHEATS).map { TabSpec(newId(), it) }

        fun defaultIds(): () -> String {
            var n = System.currentTimeMillis()
            return { "t" + (n++).toString(36) }
        }
    }
}
