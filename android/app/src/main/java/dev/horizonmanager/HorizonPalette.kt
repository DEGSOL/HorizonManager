package dev.horizonmanager

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal object HorizonPalette {
    val background = Color(0xFF0A0C10)
    val tint = Color(0xFFF6A15A)
    val link = Color(0xFFE8B884)
    val text = Color(0xFFF5F5F3)
    val muted = Color(0xFFA5A8B0)
    val surface = Color(0xFF14171D)
    val card = Color(0xFF20242B)
    val elevated = Color(0xFF30353D)
    val glassBase = Color(0xFF161616)
    val glassRaised = Color(0xFF242424)
    val glassSelection = Color(0xFF414141)
    val tertiaryText = lerp(muted, background, .24f)
    val success = Color(0xFF9BC9A7)
    val warning = Color(0xFFDBBA83)
    val danger = Color(0xFFF2A6AA)

    fun panel(amount: Float) = lerp(background, tint, amount)
}
