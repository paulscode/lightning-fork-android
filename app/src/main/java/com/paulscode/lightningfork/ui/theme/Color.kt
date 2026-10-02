package com.paulscode.lightningfork.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// The dashboard's dark theme (global-styles/custom.scss): a storm-navy sky and
// one electric-blue accent, the bolt's.

val Page = Color(0xFF050B18)
val Surface = Color(0xFF0C1630)
val SurfaceRaised = Color(0xFF122044)
val SurfaceSoft = Color(0xFF080F22)
val InputBg = Color(0xFF070D1C)

val Border = Color(0x2E5899F2)        // rgba(88,153,242,.18)
val BorderStrong = Color(0x575899F2)  // rgba(88,153,242,.34)

val TextPrimary = Color(0xFFE8F1FF)
val TextMuted = Color(0xFF8FA6CF)
val TextFaint = Color(0xFF6A7FA8)

val Accent = Color(0xFF4E93FF)
val AccentDeep = Color(0xFF2F6FE8)
val AccentSky = Color(0xFF8FD0FF)
val AccentGlow = Color(0x594E93FF)    // rgba(78,147,255,.35)
val ChainBadgeText = Color(0xFFC6E2FC)
val ChainBadgeBg = Color(0x294E93FF)

val Success = Color(0xFF00CD98)
val Warning = Color(0xFFF6B900)
val Danger = Color(0xFFF46E6E)

/** Bitcoin's orange, for the on-chain balance's mark (the dashboard's app icon). */
val BitcoinOrange = Color(0xFFFF9F2E)

// The dashboard's gradient, its sky end deepened so white text on it stays
// readable on a phone in daylight.
val AccentGradient = Brush.horizontalGradient(
    0f to AccentDeep,
    0.6f to Accent,
    1f to Color(0xFF6AAEFF),
)

val LightningGradient = Brush.linearGradient(listOf(AccentDeep, Color(0xFF7CC6FF)))
val BitcoinGradient = Brush.linearGradient(listOf(Color(0xFFFFBE40), Color(0xFFFF8D23)))
