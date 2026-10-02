package com.paulscode.lightningfork.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paulscode.lightningfork.ui.theme.Accent
import com.paulscode.lightningfork.ui.theme.AccentGlow
import com.paulscode.lightningfork.ui.theme.AccentGradient
import com.paulscode.lightningfork.ui.theme.BorderStrong
import com.paulscode.lightningfork.ui.theme.SurfaceRaised
import com.paulscode.lightningfork.ui.theme.TextPrimary

/** Presses in slightly, like the dashboard's buttons lift on hover. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    return s
}

/** The accent button: the bolt's gradient with its glow. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 56.dp,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .height(height)
            .scale(pressScale(source))
            .shadow(if (enabled) 18.dp else 0.dp, shape, ambientColor = AccentGlow, spotColor = Accent)
            .clip(shape)
            .background(AccentGradient)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                interactionSource = source,
                indication = androidx.compose.material3.ripple(color = Color.White),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

/** The quieter button: a raised surface with the accent's hairline. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 56.dp,
    contentColor: Color = TextPrimary,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .height(height)
            .scale(pressScale(source))
            .clip(shape)
            .background(SurfaceRaised)
            .border(BorderStroke(1.dp, BorderStrong), shape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                interactionSource = source,
                indication = androidx.compose.material3.ripple(color = Accent),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor)
        }
    }
}

/** A text button for the third choice on a screen. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Accent,
    enabled: Boolean = true,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
fun FullWidthPrimary(text: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false, icon: ImageVector? = null) =
    PrimaryButton(text, onClick, Modifier.fillMaxWidth(), enabled, loading, icon)
