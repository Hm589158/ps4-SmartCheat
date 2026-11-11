package com.ps4smartcheat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.ps4smartcheat.app.ui.SmartCheatTheme
import com.ps4smartcheat.app.ui.appContainer
import com.ps4smartcheat.app.ui.screens.AddressesScreen
import com.ps4smartcheat.app.ui.screens.CheatsScreen
import com.ps4smartcheat.app.ui.screens.CodeScreen
import com.ps4smartcheat.app.ui.screens.ConnectionScreen
import com.ps4smartcheat.app.ui.screens.DebuggerScreen
import com.ps4smartcheat.app.ui.screens.DiagnosticsScreen
import com.ps4smartcheat.app.ui.screens.EditorScreen
import com.ps4smartcheat.app.ui.screens.HelpScreen
import com.ps4smartcheat.app.ui.screens.HexViewerScreen
import com.ps4smartcheat.app.ui.screens.InjectScreen
import com.ps4smartcheat.app.ui.screens.PatternSearchScreen
import com.ps4smartcheat.app.ui.screens.PointerScanScreen
import com.ps4smartcheat.app.ui.screens.ProcessesScreen
import com.ps4smartcheat.app.ui.screens.ScannerScreen
import com.ps4smartcheat.app.ui.screens.SettingsScreen
import com.ps4smartcheat.app.ui.screens.WatchScreen
import com.ps4smartcheat.app.ui.tabs.TabCountButton
import com.ps4smartcheat.app.ui.tabs.TabOverview
import com.ps4smartcheat.app.ui.tabs.TabStrip
import com.ps4smartcheat.app.ui.tabs.ToolLauncher
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.tabs.TabSpec
import com.ps4smartcheat.core.tabs.Tool
import com.ps4smartcheat.core.tabs.ToolArgs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val container = appContainer()
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            SmartCheatTheme(settings.theme) {
                AppRoot()
                NotificationPermissionOnConnect()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as SmartCheatApp).container.onAppForeground()
    }
}

/** What the content area shows besides the active tab. */
private enum class Page { TAB, NEW_TAB, OVERVIEW }

/**
 * The app window, organised like a browser: a toolbar, a strip of tool tabs, and the active tab's tool. Tools open in
 * their own tab (a single-instance tool such as the scanner is switched to instead); every tab keeps its state and view
 * models while another tab is shown, and all tabs share the app's one PS4 connection and selected process.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val container = appContainer()
    val tabs = container.tabs
    val list by tabs.tabs.collectAsStateWithLifecycle()
    val activeId by tabs.activeId.collectAsStateWithLifecycle()
    val active = list.firstOrNull { it.id == activeId } ?: list.first()
    val connection by container.backend.state.collectAsStateWithLifecycle()
    val selected by container.selectedProcess.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(Page.TAB) }
    val holder = rememberSaveableStateHolder()
    val activity = LocalActivity.current
    val open: (Tool, ToolArgs) -> Unit = { tool, args -> page = Page.TAB; tabs.open(tool, args) }

    LaunchedEffect(Unit) {
        container.log.messages.collect { snackbar.showSnackbar(it) }
    }
    // Forget the saved UI state of closed tabs (their view models are cleared by the tab manager).
    val known = remember { mutableSetOf<String>() }
    LaunchedEffect(list) {
        val ids = list.map { it.id }.toSet()
        (known - ids).forEach { holder.removeState(it) }
        known.clear(); known.addAll(ids)
    }
    // Back: leave the new-tab page / overview, else return to the tab used before; else leave the app.
    BackHandler {
        when {
            page != Page.TAB -> page = Page.TAB
            !tabs.back() -> activity?.moveTaskToBack(true)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                when (page) { Page.TAB -> active.tool.title; Page.NEW_TAB -> "New tab"; Page.OVERVIEW -> "Tabs" },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val sub = when (val c = connection) {
                                is ConnectionState.Connected -> "● ${c.info.host}" + (selected?.let { " · ${it.name} (${it.pid})" + if (it.exited) " — exited" else "" } ?: "")
                                is ConnectionState.Connecting -> "Connecting…"
                                is ConnectionState.Reconnecting -> "Reconnecting ${c.attempt}/${c.maxAttempts}…"
                                is ConnectionState.Failed -> "Connection lost"
                                ConnectionState.Disconnected -> "Not connected"
                            }
                            Text(sub, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        // Every tool is on the new-tab page (+); only Settings and the tab counter stay here.
                        val tool = active.tool.takeIf { page == Page.TAB }
                        if (tool != Tool.SETTINGS) {
                            IconButton(onClick = { open(Tool.SETTINGS, ToolArgs()) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                        }
                        TabCountButton(list.size) { page = if (page == Page.OVERVIEW) Page.TAB else Page.OVERVIEW }
                    },
                )
                TabStrip(
                    tabs = list,
                    activeId = if (page == Page.TAB) active.id else "",
                    onSelect = { page = Page.TAB; tabs.focus(it) },
                    onClose = { tabs.close(it) },
                    onMoveTo = { id, i -> tabs.moveTo(id, i) },
                    onDuplicate = { page = Page.TAB; tabs.duplicate(it) },
                    onCloseOthers = { page = Page.TAB; tabs.closeOthers(it) },
                    onNewTab = { page = Page.NEW_TAB },
                )
                HorizontalDivider()
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxWidth().widthIn(max = 900.dp).fillMaxSize().testTag("tabContent")) {
                when (page) {
                    Page.NEW_TAB -> ToolLauncher(
                        openTools = list.map { it.tool }.toSet(),
                        onOpen = { t -> page = Page.TAB; tabs.open(t, ToolArgs(), reuse = false) },
                        onCancel = { page = Page.TAB },
                    )
                    Page.OVERVIEW -> TabOverview(
                        tabs = list,
                        activeId = active.id,
                        onSelect = { page = Page.TAB; tabs.focus(it) },
                        onClose = { tabs.close(it) },
                        onMove = { id, d -> tabs.move(id, d) },
                        onNewTab = { page = Page.NEW_TAB },
                        onCloseOthers = { tabs.closeOthers(it) },
                    )
                    Page.TAB -> key(active.id) {
                        val owner = remember(active.id) { container.tabStores.owner(active.id) }
                        holder.SaveableStateProvider(active.id) {
                            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                                ToolScreen(active, open)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The tool of one tab. Links to other tools ("view memory", "disassemble"…) open them in a tab. */
@Composable
private fun ToolScreen(tab: TabSpec, open: (Tool, ToolArgs) -> Unit) {
    val a = tab.args
    val at = { tool: Tool -> { address: Long -> open(tool, ToolArgs(address = address)) } }
    when (tab.tool) {
        Tool.CONNECT -> ConnectionScreen(onConnected = { open(Tool.PROCESSES, ToolArgs()) })
        Tool.PROCESSES -> ProcessesScreen(onOpenScanner = { open(Tool.SCANNER, ToolArgs()) })
        Tool.SCANNER -> ScannerScreen(
            onEdit = { address, type -> open(Tool.EDITOR, ToolArgs(address = address, type = type)) },
            onPatternSearch = { open(Tool.PATTERN, ToolArgs()) },
        )
        Tool.ADDRESSES -> AddressesScreen(
            onEdit = { address, type -> open(Tool.EDITOR, ToolArgs(address = address, type = type)) },
            onViewMemory = at(Tool.HEX),
            onPointerScan = { address, type -> open(Tool.POINTER, ToolArgs(address = address, type = type)) },
            onWatch = { address, size, writes -> open(Tool.WATCH, ToolArgs(address = address, size = size, writes = writes)) },
        )
        Tool.CHEATS -> CheatsScreen()
        Tool.EDITOR -> EditorScreen(a.address, a.type, onViewMemory = at(Tool.HEX))
        Tool.HEX -> HexViewerScreen(
            a.address,
            onOpenEditor = { address -> open(Tool.EDITOR, ToolArgs(address = address)) },
            onDisassemble = at(Tool.CODE),
        )
        Tool.CODE -> CodeScreen(
            a.address,
            onViewMemory = at(Tool.HEX),
            onWatch = { address, size, writes -> open(Tool.WATCH, ToolArgs(address = address, size = size, writes = writes)) },
            onInject = at(Tool.INJECT),
            onDebug = at(Tool.DEBUGGER),
        )
        Tool.INJECT -> InjectScreen(a.address, onOpenCode = at(Tool.CODE))
        Tool.WATCH -> WatchScreen(a.address, a.size, a.writes, onOpenCode = at(Tool.CODE))
        Tool.DEBUGGER -> DebuggerScreen(a.address, onOpenCode = at(Tool.CODE))
        Tool.POINTER -> PointerScanScreen(a.address, a.type)
        Tool.PATTERN -> PatternSearchScreen(onOpen = at(Tool.HEX))
        Tool.SETTINGS -> SettingsScreen(onDiagnostics = { open(Tool.DIAGNOSTICS, ToolArgs()) }, onHelp = { open(Tool.HELP, ToolArgs()) })
        Tool.DIAGNOSTICS -> DiagnosticsScreen()
        Tool.HELP -> HelpScreen()
    }
}

/**
 * Android 13+: the "connected" notification of the background connection service needs the notification
 * permission. Asked once, after the first successful connection (not at app start). Without it the connection is
 * still kept alive; only the notification is hidden.
 */
@androidx.compose.runtime.Composable
private fun NotificationPermissionOnConnect() {
    if (android.os.Build.VERSION.SDK_INT < 33 || android.os.Build.FINGERPRINT == "robolectric") return
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = appContainer()
    val state by container.backend.state.collectAsStateWithLifecycle()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    androidx.compose.runtime.LaunchedEffect(state is ConnectionState.Connected) {
        if (state !is ConnectionState.Connected) return@LaunchedEffect
        val prefs = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
