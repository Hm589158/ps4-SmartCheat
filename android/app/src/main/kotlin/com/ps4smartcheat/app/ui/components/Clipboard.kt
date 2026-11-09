package com.ps4smartcheat.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Puts [text] on the system clipboard. Returns false when the clipboard is unavailable. */
fun copyText(context: Context, label: String, text: String): Boolean {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    return true
}
