package com.ps4smartcheat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ps4smartcheat.app.ui.components.StatusPill
import com.ps4smartcheat.app.ui.components.Tone
import com.ps4smartcheat.core.debug.FrameConfidence
import com.ps4smartcheat.core.debug.StackFrame
import com.ps4smartcheat.core.util.Address

/**
 * Callers at a watchpoint hit, innermost first: #0 is the hit instruction, then each `call` that led there. Tapping a
 * row opens it ([onOpen] gets the address to show). Frames that are probably stale values are hidden behind a toggle.
 *
 * @param address where a frame's call instruction is now (saved sessions resolve module offsets after a restart)
 */
@Composable
fun CallStackView(
    hitAddress: Long,
    hitText: String?,
    frames: List<StackFrame>,
    relative: (Long) -> String,
    onOpen: (Long) -> Unit,
    address: (StackFrame) -> Long = { it.callAddress },
    onCopy: (() -> Unit)? = null,
    tag: String = "callStack",
) {
    var showWeak by remember { mutableStateOf(false) }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val weak = frames.count { it.confidence == FrameConfidence.WEAK }
    val shown = if (showWeak) frames else frames.filter { it.confidence != FrameConfidence.WEAK }
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Call stack", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (onCopy != null) TextButton(onClick = onCopy, modifier = Modifier.testTag("$tag-copy")) { Text("Copy") }
        }
        StackRow(0, hitAddress, hitText ?: "(hit instruction)", relative(hitAddress), "hit", Tone.GOOD, mono, dim, Modifier.clickable { onOpen(hitAddress) }.testTag("$tag-0"))
        if (frames.isEmpty()) {
            Text("No callers found on the stack (not captured, or code that keeps no return address there).", style = MaterialTheme.typography.bodySmall, color = dim)
        }
        shown.forEachIndexed { i, f ->
            val a = address(f)
            val tone = when (f.confidence) {
                FrameConfidence.CONFIRMED, FrameConfidence.FRAME_CHAIN -> Tone.GOOD
                FrameConfidence.PROBABLE -> Tone.NEUTRAL
                FrameConfidence.WEAK -> Tone.WARN
            }
            StackRow(i + 1, a, f.callText, relative(a), f.confidence.label, tone, mono, dim, Modifier.clickable { onOpen(a) }.testTag("$tag-${i + 1}"))
        }
        if (weak > 0) {
            TextButton(onClick = { showWeak = !showWeak }) { Text(if (showWeak) "Hide possibly stale entries" else "Show $weak possibly stale entr" + if (weak == 1) "y" else "ies") }
        }
        Text(
            "Found by reading the stack at the hit and checking the call before each return address; it can include stale " +
                "entries or miss tail calls.",
            style = MaterialTheme.typography.bodySmall, color = dim,
        )
    }
}

@Composable
private fun StackRow(
    n: Int, address: Long, text: String, relative: String, label: String, tone: Tone,
    mono: androidx.compose.ui.text.TextStyle, dim: androidx.compose.ui.graphics.Color, modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("#$n", style = mono, color = dim)
            Text(Address.format(address), style = mono)
            Text(text, style = mono, modifier = Modifier.weight(1f))
            StatusPill(label, tone)
        }
        if (relative.isNotBlank()) Text("     $relative", style = mono, color = dim)
    }
}
