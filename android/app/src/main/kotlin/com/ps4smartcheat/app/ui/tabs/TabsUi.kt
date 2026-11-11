package com.ps4smartcheat.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ps4smartcheat.core.tabs.TabSpec
import com.ps4smartcheat.core.tabs.Tool

/** Icon of a tool (material-icons-core only). */
fun Tool.icon(): ImageVector = when (this) {
    Tool.CONNECT -> Icons.Filled.Home
    Tool.PROCESSES -> Icons.AutoMirrored.Filled.List
    Tool.SCANNER -> Icons.Filled.Search
    Tool.ADDRESSES -> Icons.Filled.Place
    Tool.CHEATS -> Icons.Filled.Star
    Tool.EDITOR -> Icons.Filled.Edit
    Tool.HEX -> Icons.Filled.Menu
    Tool.CODE -> Icons.Filled.Build
    Tool.INJECT -> Icons.Filled.AddCircle
    Tool.WATCH -> Icons.Filled.Notifications
    Tool.DEBUGGER -> Icons.Filled.PlayArrow
    Tool.POINTER -> Icons.Filled.LocationOn
    Tool.PATTERN -> Icons.Filled.Create
    Tool.SETTINGS -> Icons.Filled.Settings
    Tool.DIAGNOSTICS -> Icons.Filled.Warning
    Tool.HELP -> Icons.Filled.Info
}

/** One line describing a tool, for the new-tab page. */
fun Tool.blurb(): String = when (this) {
    Tool.CONNECT -> "Connect to the PS4 (ps4debug-NG)"
    Tool.PROCESSES -> "Pick the game process"
    Tool.SCANNER -> "Find values in memory"
    Tool.ADDRESSES -> "Saved addresses of this game"
    Tool.CHEATS -> "Cheat library, ON/OFF switches, GoldHEN"
    Tool.EDITOR -> "Read and write one value"
    Tool.HEX -> "Hex view and byte patches"
    Tool.CODE -> "Disassembler and Assembly patches"
    Tool.INJECT -> "Code cave: inject your own code"
    Tool.WATCH -> "What writes / accesses an address"
    Tool.DEBUGGER -> "Breakpoint, step, register trace"
    Tool.POINTER -> "Pointer chains that survive restarts"
    Tool.PATTERN -> "Byte patterns and text"
    Tool.SETTINGS -> "Editing, scanning, connection"
    Tool.DIAGNOSTICS -> "Connection and game checks"
    Tool.HELP -> "How everything works"
}

/** Text of a tab: the tool's short name, plus the address for tools opened at one. */
fun TabSpec.label(): String = tool.label + (args.describe()?.let { " · " + it.removePrefix("0x").trimStart('0').ifEmpty { "0" } } ?: "")

/**
 * The tab strip under the toolbar: one chip per tab (tap = switch, × = close, long press = move / duplicate / close
 * others), and + for a new tab. Scrolls horizontally and keeps the active tab in view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabStrip(
    tabs: List<TabSpec>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onDuplicate: (String) -> Unit,
    onCloseOthers: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    val activeIndex = tabs.indexOfFirst { it.id == activeId }
    LaunchedEffect(activeId, tabs.size) { if (activeIndex >= 0) state.animateScrollToItem(activeIndex) }
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f).testTag("tabStrip"), state = state, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(tabs, key = { _, t -> t.id }) { index, t ->
                val selected = t.id == activeId
                var menu by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
                        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        border = if (selected) BorderStroke(1.dp, cs.primary.copy(alpha = 0.6f)) else null,
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .semantics { this.selected = selected }
                            .combinedClickable(onClick = { onSelect(t.id) }, onLongClick = { menu = true })
                            .testTag("tabChip-${t.tool.name}"),
                    ) {
                        Row(Modifier.padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(t.tool.icon(), contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                t.label(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.widthIn(max = 160.dp),
                            )
                            IconButton(onClick = { onClose(t.id) }, modifier = Modifier.size(30.dp).testTag("tabClose-${t.tool.name}")) {
                                Icon(Icons.Filled.Close, contentDescription = "Close ${t.tool.label} tab", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Move left") }, enabled = index > 0, onClick = { menu = false; onMove(t.id, -1) })
                        DropdownMenuItem(text = { Text("Move right") }, enabled = index < tabs.size - 1, onClick = { menu = false; onMove(t.id, 1) })
                        if (!t.tool.singleInstance) DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate(t.id) })
                        DropdownMenuItem(text = { Text("Close other tabs") }, enabled = tabs.size > 1, onClick = { menu = false; onCloseOthers(t.id) })
                        DropdownMenuItem(text = { Text("Close") }, onClick = { menu = false; onClose(t.id) })
                    }
                }
            }
        }
        IconButton(onClick = onNewTab, modifier = Modifier.testTag("newTab")) { Icon(Icons.Filled.Add, contentDescription = "New tab") }
    }
}

/** The tab counter in the toolbar (opens the tab overview), like Chrome's square with the number of tabs. */
@Composable
fun TabCountButton(count: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.testTag("tabCount").semantics { contentDescription = "Tabs ($count)" }) {
        Surface(shape = RoundedCornerShape(6.dp), color = cs.surface, border = BorderStroke(2.dp, cs.onSurface), modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (count > 99) "99+" else count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The new-tab page: every tool, grouped; a single-instance tool that is already open is switched to. */
@Composable
fun ToolLauncher(openTools: Set<Tool>, onOpen: (Tool) -> Unit, onCancel: () -> Unit) {
    val groups = listOf(
        "Start" to listOf(Tool.CONNECT, Tool.PROCESSES),
        "Memory" to listOf(Tool.SCANNER, Tool.ADDRESSES, Tool.EDITOR, Tool.HEX, Tool.PATTERN, Tool.POINTER),
        "Code" to listOf(Tool.CODE, Tool.INJECT, Tool.WATCH, Tool.DEBUGGER),
        "Library and app" to listOf(Tool.CHEATS, Tool.SETTINGS, Tool.DIAGNOSTICS, Tool.HELP),
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = Modifier.fillMaxSize().testTag("toolLauncher"),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New tab", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel, modifier = Modifier.testTag("launcherCancel")) { Text("Cancel") }
            }
        }
        groups.forEach { (title, tools) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }
            items(tools, key = { it.name }) { t -> ToolCard(t, already = t.singleInstance && t in openTools) { onOpen(t) } }
        }
    }
}

@Composable
private fun ToolCard(tool: Tool, already: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("tool-${tool.name}"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(tool.icon(), contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(tool.blurb(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (already) Text("open — switch to it", style = MaterialTheme.typography.labelSmall, color = cs.secondary)
        }
    }
}

/**
 * The tab overview (Chrome's tab switcher): a card per tab with its tool and address; tap to switch, × to close,
 * ‹ › to reorder. Also: new tab and close all others.
 */
@Composable
fun TabOverview(
    tabs: List<TabSpec>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onNewTab: () -> Unit,
    onCloseOthers: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize().testTag("tabOverview"),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${tabs.size} tab" + if (tabs.size == 1) "" else "s", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onCloseOthers(activeId) }, enabled = tabs.size > 1) { Text("Close others") }
                IconButton(onClick = onNewTab) { Icon(Icons.Filled.Add, contentDescription = "New tab from overview") }
            }
        }
        items(tabs, key = { it.id }) { t ->
            val selected = t.id == activeId
            val index = tabs.indexOf(t)
            Surface(
                onClick = { onSelect(t.id) },
                shape = RoundedCornerShape(16.dp),
                color = if (selected) cs.primaryContainer else cs.surfaceContainerLow,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().testTag("overviewCard-${t.tool.name}"),
            ) {
                Column(Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.tool.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t.tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onClose(t.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close ${t.tool.label}") }
                    }
                    Text(
                        t.args.describe() ?: if (selected) "current tab" else " ",
                        style = MaterialTheme.typography.bodySmall, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        IconButton(onClick = { onMove(t.id, -1) }, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Move ${t.tool.label} left")
                        }
                        IconButton(onClick = { onMove(t.id, 1) }, enabled = index < tabs.size - 1, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Move ${t.tool.label} right")
                        }
                    }
                }
            }
        }
    }
}
