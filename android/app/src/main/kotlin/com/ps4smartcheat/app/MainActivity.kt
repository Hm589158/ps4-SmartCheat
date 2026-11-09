package com.ps4smartcheat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ps4smartcheat.app.ui.SmartCheatTheme
import com.ps4smartcheat.app.ui.appContainer
import com.ps4smartcheat.app.ui.screens.AddressesScreen
import com.ps4smartcheat.app.ui.screens.CodeScreen
import com.ps4smartcheat.app.ui.screens.WatchScreen
import com.ps4smartcheat.app.ui.screens.InjectScreen
import com.ps4smartcheat.app.ui.screens.CheatsScreen
import com.ps4smartcheat.app.ui.screens.ConnectionScreen
import com.ps4smartcheat.app.ui.screens.DiagnosticsScreen
import com.ps4smartcheat.app.ui.screens.EditorScreen
import com.ps4smartcheat.app.ui.screens.HelpScreen
import com.ps4smartcheat.app.ui.screens.HexViewerScreen
import com.ps4smartcheat.app.ui.screens.PatternSearchScreen
import com.ps4smartcheat.app.ui.screens.PointerScanScreen
import com.ps4smartcheat.app.ui.screens.ProcessesScreen
import com.ps4smartcheat.app.ui.screens.ScannerScreen
import com.ps4smartcheat.app.ui.screens.SettingsScreen
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Address

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

/** Screens opened on top of a tab: they show a back arrow. */
private val backRoutes = setOf(Routes.EDITOR, Routes.HEX, Routes.PATTERN, Routes.POINTER, Routes.DIAGNOSTICS, Routes.HELP, Routes.CODE, Routes.WATCH, Routes.INJECT)

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.CONNECTION, "Connect", Icons.Filled.Home),
    Tab(Routes.PROCESSES, "Processes", Icons.AutoMirrored.Filled.List),
    Tab(Routes.SCANNER, "Scanner", Icons.Filled.Search),
    Tab(Routes.ADDRESSES, "Addresses", Icons.Filled.Place),
    Tab(Routes.CHEATS, "Cheats", Icons.Filled.Star),
)

object Routes {
    const val CONNECTION = "connection"
    const val PROCESSES = "processes"
    const val SCANNER = "scanner"
    const val EDITOR = "editor"
    const val CHEATS = "cheats"
    const val ADDRESSES = "addresses"
    const val SETTINGS = "settings"
    const val HEX = "hex"
    const val PATTERN = "pattern"
    const val POINTER = "pointer"
    const val DIAGNOSTICS = "diagnostics"
    const val HELP = "help"
    const val CODE = "code"
    const val WATCH = "watch"
    const val INJECT = "inject"

    fun inject(address: Long): String = INJECT + "?address=" + java.lang.Long.toHexString(address)

    fun code(address: Long?): String = CODE + "?address=" + (address?.let { java.lang.Long.toHexString(it) } ?: "")

    fun watch(address: Long?, size: Int?, writes: Boolean?): String =
        WATCH + "?address=" + (address?.let { java.lang.Long.toHexString(it) } ?: "") + "&size=" + (size ?: "") + "&writes=" + (writes ?: "")

    fun pointer(address: Long?, type: ValueType?): String =
        POINTER + "?address=" + (address?.let { java.lang.Long.toHexString(it) } ?: "") + "&type=" + (type?.name ?: "")

    fun hex(address: Long?): String = HEX + "?address=" + (address?.let { java.lang.Long.toHexString(it) } ?: "")

    fun editor(address: Long?, type: ValueType?): String =
        EDITOR + "?address=" + (address?.let { java.lang.Long.toHexString(it) } ?: "") + "&type=" + (type?.name ?: "")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val container = appContainer()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route?.substringBefore('?')
    val connection by container.backend.state.collectAsStateWithLifecycle()
    val selected by container.selectedProcess.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    // Tapping the tab you are in (e.g. from Pointer scan opened from Addresses) returns to its root.
    val openTab: (String) -> Unit = { r -> if (!nav.popBackStack(r, inclusive = false)) nav.navigateTab(r) }

    LaunchedEffect(Unit) {
        container.log.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(titleFor(route), maxLines = 1)
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
                navigationIcon = {
                    if (route in backRoutes) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    if (route != Routes.EDITOR && route != Routes.HEX) {
                        IconButton(onClick = { nav.navigate(Routes.editor(null, null)) }) { Icon(Icons.Filled.Edit, contentDescription = "Memory editor") }
                    }
                    if (route != Routes.CODE) {
                        IconButton(onClick = { nav.navigate(Routes.code(null)) }) { Icon(Icons.Filled.Build, contentDescription = "Code") }
                    }
                    if (route != Routes.HELP) {
                        IconButton(onClick = { nav.navigate(Routes.HELP) }) { Icon(Icons.Filled.Info, contentDescription = "Guide") }
                    }
                    if (route != Routes.SETTINGS) {
                        IconButton(onClick = { nav.navigateTab(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
      Row(Modifier.padding(padding)) {
        // Tablets and landscape phones: a side rail instead of the bottom bar, so content keeps its height.
        if (wide) {
            NavigationRail {
                tabs.forEach { tab ->
                    NavigationRailItem(
                        selected = route == tab.route,
                        onClick = { openTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
        }
        NavHost(nav, startDestination = Routes.CONNECTION, modifier = Modifier.weight(1f).widthIn(max = 900.dp)) {
            composable(Routes.CONNECTION) { ConnectionScreen(onConnected = { nav.navigateTab(Routes.PROCESSES) }) }
            composable(Routes.PROCESSES) { ProcessesScreen(onOpenScanner = { nav.navigateTab(Routes.SCANNER) }) }
            composable(Routes.SCANNER) { ScannerScreen(onEdit = { a, t -> nav.navigate(Routes.editor(a, t)) }, onPatternSearch = { nav.navigate(Routes.PATTERN) }) }
            composable(
                Routes.EDITOR + "?address={address}&type={type}",
                arguments = listOf(
                    navArgument("address") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val address = entry.arguments?.getString("address")?.takeIf { it.isNotBlank() }?.let { Address.parse(it) }
                val type = entry.arguments?.getString("type")?.let { t -> ValueType.entries.firstOrNull { it.name == t } }
                EditorScreen(address, type, onViewMemory = { a -> nav.navigate(Routes.hex(a)) })
            }
            composable(
                Routes.HEX + "?address={address}",
                arguments = listOf(navArgument("address") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val address = entry.arguments?.getString("address")?.takeIf { it.isNotBlank() }?.let { Address.parse(it) }
                HexViewerScreen(address, onOpenEditor = { a -> nav.navigate(Routes.editor(a, null)) }, onDisassemble = { a -> nav.navigate(Routes.code(a)) })
            }
            composable(Routes.ADDRESSES) {
                AddressesScreen(
                    onEdit = { a, t -> nav.navigate(Routes.editor(a, t)) },
                    onViewMemory = { a -> nav.navigate(Routes.hex(a)) },
                    onPointerScan = { a, t -> nav.navigate(Routes.pointer(a, t)) },
                    onWatch = { a, size, writes -> nav.navigate(Routes.watch(a, size, writes)) },
                )
            }
            composable(
                Routes.CODE + "?address={address}",
                arguments = listOf(navArgument("address") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val address = entry.arguments?.getString("address")?.takeIf { it.isNotBlank() }?.let { Address.parse(it) }
                CodeScreen(
                    address,
                    onViewMemory = { a -> nav.navigate(Routes.hex(a)) },
                    onWatch = { a, size, writes -> nav.navigate(Routes.watch(a, size, writes)) },
                    onInject = { a -> nav.navigate(Routes.inject(a)) },
                )
            }
            composable(
                Routes.INJECT + "?address={address}",
                arguments = listOf(navArgument("address") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val address = entry.arguments?.getString("address")?.takeIf { it.isNotBlank() }?.let { Address.parse(it) }
                InjectScreen(address, onOpenCode = { a -> nav.navigate(Routes.code(a)) })
            }
            composable(
                Routes.WATCH + "?address={address}&size={size}&writes={writes}",
                arguments = listOf(
                    navArgument("address") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("size") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("writes") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val address = entry.arguments?.getString("address")?.takeIf { it.isNotBlank() }?.let { Address.parse(it) }
                val size = entry.arguments?.getString("size")?.toIntOrNull()
                val writes = entry.arguments?.getString("writes")?.toBooleanStrictOrNull()
                WatchScreen(address, size, writes, onOpenCode = { a -> nav.navigate(Routes.code(a)) })
            }
            composable(
                Routes.POINTER + "?address={address}&type={type}",
                arguments = listOf(
                    navArgument("address") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val address = entry.arguments?.getString("address")?.takeIf { it.isNotBlank() }?.let { Address.parse(it) }
                val type = entry.arguments?.getString("type")?.let { t -> ValueType.entries.firstOrNull { it.name == t } }
                PointerScanScreen(address, type)
            }
            composable(Routes.PATTERN) { PatternSearchScreen(onOpen = { a -> nav.navigate(Routes.hex(a)) }) }
            composable(Routes.CHEATS) { CheatsScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) }, onHelp = { nav.navigate(Routes.HELP) }) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen() }
            composable(Routes.HELP) { HelpScreen() }
        }
      }
    }
}

private fun titleFor(route: String?): String = when (route) {
    Routes.CONNECTION -> "Connection"
    Routes.PROCESSES -> "Running processes"
    Routes.SCANNER -> "Memory scanner"
    Routes.EDITOR -> "Memory editor"
    Routes.HEX -> "Memory viewer"
    Routes.PATTERN -> "Pattern search"
    Routes.POINTER -> "Pointer scan"
    Routes.DIAGNOSTICS -> "Diagnostics"
    Routes.HELP -> "Guide"
    Routes.CODE -> "Code"
    Routes.WATCH -> "Watchpoint"
    Routes.INJECT -> "Code injection"
    Routes.CHEATS -> "Saved cheats"
    Routes.ADDRESSES -> "Address list"
    Routes.SETTINGS -> "Settings"
    else -> "PS4 SmartCheat"
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
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
