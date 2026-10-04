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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.util.Format

/**
 * An amount that counts to its new value when it changes, the way the
 * dashboard's balances do, instead of jumping. The first value shows as is.
 * It never shows the new value before counting to it, counts in whole sats
 * (exact at any size), and shrinks to fit its width rather than cutting a
 * figure off at large font sizes. Tabular figures keep the digits still.
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
    /** Named for its chain, where amounts of both are shown. */
    coin: com.paulscode.lightningfork.util.Coin? = null,
) {
    // What is on screen at rest, and the count under way: until the count
    // for a new value starts, the old value stays; a new value mid-count
    // carries on from where the count is.
    var resting by remember { mutableLongStateOf(sats) }
    var countFrom by remember { mutableLongStateOf(sats) }
    var countTo by remember { mutableLongStateOf(sats) }
    val progress = remember { Animatable(1f) }
    fun current(): Long =
        if (progress.isRunning) countFrom + ((countTo - countFrom) * progress.value.toDouble()).toLong() else resting
    LaunchedEffect(sats) {
        if (sats == resting && !progress.isRunning) return@LaunchedEffect
        countFrom = current()
        countTo = sats
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
        resting = sats
    }
    val shown = current()

    // Shrinks to fit; starts again from full size when the figure gets
    // shorter, so a big number that once shrank does not keep others small.
    // Sized for the longer of where the count starts and ends, so crossing a
    // digit while counting doesn't make it measure (and blank) again.
    val text = Format.amount(shown, unit)
    val sizeKey = maxOf(Format.amount(sats, unit).length, Format.amount(resting, unit).length)
    var scale by remember(unit, sizeKey) { mutableFloatStateOf(1f) }
    var fitted by remember(unit, sizeKey) { mutableFloatStateOf(0f) }
    Row(
        modifier = modifier.semantics { contentDescription = Format.amountWithUnit(sats, unit, coin) },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text,
            style = style.copy(fontFeatureSettings = "tnum", fontSize = style.fontSize * scale),
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .weight(1f, fill = false)
                .drawWithContent { if (fitted >= scale) drawContent() },
            onTextLayout = { layout ->
                if (layout.hasVisualOverflow && scale > 0.4f) scale *= 0.9f else fitted = scale
            },
        )
        if (showUnit) {
            Text(
                Format.unitLabel(unit, sats, coin),
                style = unitStyle,
                color = unitColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
            )
        }
    }
}
