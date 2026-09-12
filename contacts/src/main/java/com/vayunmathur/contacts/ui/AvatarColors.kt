package com.vayunmathur.contacts.ui

import androidx.compose.ui.graphics.Color

fun getAvatarColor(id: Long): Color {
    val colors = listOf(
        Color(0xFF6C3800),
        Color(0xFF00502A),
        Color(0xFF8B0053),
        Color(0xFF891916),
        Color(0xFF004B5B),
        Color(0xFF5528A1),
    )
    val index = Math.floorMod(id, colors.size.toLong()).toInt()
    return colors[index]
}
