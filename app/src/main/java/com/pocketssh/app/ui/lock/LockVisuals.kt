package com.pocketssh.app.ui.lock

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pocketssh.app.data.LockAppearance
import com.pocketssh.app.data.LockGlyph
import com.pocketssh.app.data.LockKeyShape

/** Null for [LockGlyph.None] — a key with no glyph draws its text label alone. */
fun LockGlyph.vector(): ImageVector? = when (this) {
    LockGlyph.None -> null
    LockGlyph.Check -> Icons.Default.Check
    LockGlyph.Call -> Icons.Default.Call
    LockGlyph.Arrow -> Icons.AutoMirrored.Filled.ArrowForward
    LockGlyph.LockOpen -> Icons.Default.LockOpen
    LockGlyph.Bolt -> Icons.Default.Bolt
    LockGlyph.Terminal -> Icons.Default.Terminal
    LockGlyph.Heart -> Icons.Default.Favorite
    LockGlyph.Star -> Icons.Default.Star
    LockGlyph.Key -> Icons.Default.VpnKey
    LockGlyph.Power -> Icons.Default.PowerSettingsNew
    LockGlyph.Shield -> Icons.Default.Shield
}

fun LockKeyShape.shape(): Shape = when (this) {
    // 42% rather than a fixed corner radius so the squircle keeps its proportions when the key
    // size slider moves.
    LockKeyShape.Circle -> CircleShape
    LockKeyShape.Squircle -> RoundedCornerShape(percent = 42)
    LockKeyShape.Rounded -> RoundedCornerShape(18.dp)
}

// Stored as Long because 0xFFRRGGBB overflows a signed Int literal; truncating back to Int here
// is exactly the ARGB word Color(colorInt) wants.
val LockAppearance.accentColor: Color get() = Color(accent.toInt())

val LockAppearance.confirmTint: Color
    get() = if (confirmColor == 0L) accentColor else Color(confirmColor.toInt())
