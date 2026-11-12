package com.ps4smartcheat.app.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Text style for code, addresses and hex: always written left to right and aligned left, also when the phone's
 * language is written right to left (in a right-to-left layout an input field otherwise starts at the right edge).
 */
@Composable
fun ltr(style: TextStyle = LocalTextStyle.current): TextStyle = style.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left)

/** Lays [content] out left to right whatever the device language (code editors and listings). */
@Composable
fun LeftToRight(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
