package com.ps4smartcheat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ps4smartcheat.app.data.SelectedProcess

/**
 * The game a tool works on, the same way on every screen: name, pid and title ID on a tinted strip (instead of a
 * plain "Process: …" line).
 */
@Composable
fun GameHeader(sel: SelectedProcess, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().testTag("gameHeader")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(sel.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull("pid ${sel.pid}", sel.titleId, sel.appVersion?.let { "v$it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What to do when a list is still empty: a short title, one line of guidance and, if useful, a button to the tool that
 * fills it.
 */
@Composable
fun EmptyState(
    title: String, text: String, modifier: Modifier = Modifier,
    icon: ImageVector? = null, action: Pair<String, () -> Unit>? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth().testTag("emptyState")) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it, contentDescription = null, tint = cs.primary, modifier = Modifier.size(32.dp)) }
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            action?.let { (label, onClick) -> FilledTonalButton(onClick = onClick) { Text(label) } }
        }
    }
}
