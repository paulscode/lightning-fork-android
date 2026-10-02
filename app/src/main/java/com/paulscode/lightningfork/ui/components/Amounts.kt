package com.paulscode.lightningfork.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.util.Format

/**
 * An amount that counts to its new value when it changes, the way the
 * dashboard's balances do, instead of jumping. The first value shows as is.
 * Tabular figures keep the digits from shifting while it counts.
 */
@Composable
fun AnimatedAmount(
    sats: Long,
    unit: AmountUnit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    unitStyle: TextStyle = style,
    color: Color = Color.Unspecified,
    unitColor: Color = TextMuted,
    showUnit: Boolean = true,
) {
    val anim = remember { Animatable(sats.toFloat()) }
    LaunchedEffect(sats) {
        if (anim.targetValue != sats.toFloat()) {
            anim.animateTo(sats.toFloat(), tween(durationMillis = 700, easing = FastOutSlowInEasing))
        }
    }
    val shown = if (anim.isRunning) anim.value.toLong() else sats
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            Format.amount(shown, unit),
            style = style.copy(fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1,
        )
        if (showUnit) {
            Text(
                Format.unitLabel(unit, sats),
                style = unitStyle,
                color = unitColor,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
                maxLines = 1,
            )
        }
    }
}

