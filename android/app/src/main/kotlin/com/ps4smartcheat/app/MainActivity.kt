package com.ps4smartcheat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
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
import com.ps4smartcheat.app.ui.screens.CheatsScreen
import com.ps4smartcheat.app.ui.screens.ConnectionScreen
import com.ps4smartcheat.app.ui.screens.EditorScreen
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
            SmartCheatTheme(settings.theme) { AppRoot() }
        }
    }
}

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
                    if (route == Routes.EDITOR || route == Routes.HEX || route == Routes.PATTERN || route == Routes.POINTER) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    if (route != Routes.EDITOR && route != Routes.HEX) {
                        IconButton(onClick = { nav.navigate(Routes.editor(null, null)) }) { Icon(Icons.Filled.Edit, contentDescription = "Memory editor") }
                    }
                    if (route != Routes.SETTINGS) {
                        IconButton(onClick = { nav.navigateTab(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        // Tapping the tab you are in (e.g. from Pointer scan opened from Addresses) returns to its root.
                        onClick = { if (!nav.popBackStack(tab.route, inclusive = false)) nav.navigateTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(nav, startDestination = Routes.CONNECTION, modifier = Modifier.padding(padding)) {
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
                HexViewerScreen(address, onOpenEditor = { a -> nav.navigate(Routes.editor(a, null)) })
            }
            composable(Routes.ADDRESSES) {
                AddressesScreen(
                    onEdit = { a, t -> nav.navigate(Routes.editor(a, t)) },
                    onViewMemory = { a -> nav.navigate(Routes.hex(a)) },
                    onPointerScan = { a, t -> nav.navigate(Routes.pointer(a, t)) },
                )
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
            composable(Routes.SETTINGS) { SettingsScreen() }
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
