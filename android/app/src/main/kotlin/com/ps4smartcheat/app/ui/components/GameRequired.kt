package com.ps4smartcheat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ps4smartcheat.app.ui.appContainer
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.tabs.Tool

/** Opens a tool in a tab from anywhere in a screen (provided by the app window). */
val LocalOpenTool = staticCompositionLocalOf<(Tool) -> Unit> { {} }

/**
 * Shown by tools that need the game: what is missing (connection or a selected game) and a button straight to the
 * tab that fixes it. Shows nothing when the game is selected.
 */
@Composable
fun GameRequiredBanner(modifier: Modifier = Modifier) {
    val c = appContainer()
    val connection by c.backend.state.collectAsStateWithLifecycle()
    val selected by c.selectedProcess.collectAsStateWithLifecycle()
    val open = LocalOpenTool.current
    when {
        connection !is ConnectionState.Connected ->
            ActionBanner("Not connected to the PS4.", "Connect", modifier.testTag("needConnection")) { open(Tool.CONNECT) }
        selected == null ->
            ActionBanner("No game selected. Pick the running game first.", "Pick game", modifier.testTag("needGame")) { open(Tool.PROCESSES) }
        else -> Unit
    }
}

/** An information card with one action. */
@Composable
fun ActionBanner(text: String, action: String, modifier: Modifier = Modifier, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = cs.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
            }
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}
