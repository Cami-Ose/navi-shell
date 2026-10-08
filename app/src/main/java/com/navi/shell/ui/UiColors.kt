package com.navi.shell.ui

import androidx.compose.ui.graphics.Color

/**
 * 界面配色。深色主题的中性取值，字段按用途命名。
 *
 * 需要换配色时改这里即可，界面其余部分只引用字段名。
 */
object UiColors {
    val Background = Color(0xFF121212)
    val Surface = Color(0xFF1E1E1E)
    val Accent = Color(0xFF64B5F6)
    val AccentBright = Color(0xFF90CAF9)
    val TextPrimary = Color(0xFFECEFF1)
    val TextSecondary = Color(0xFFB0BEC5)
    val TextDim = Color(0x66B0BEC5)
    val Warning = Color(0xFFFFB74D)
    val Error = Color(0xFFEF5350)
}
