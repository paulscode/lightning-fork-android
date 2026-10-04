package com.paulscode.lightningfork.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.paulscode.lightningfork.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.ui.theme.Accent
import com.paulscode.lightningfork.ui.theme.Border
import com.paulscode.lightningfork.ui.theme.BorderStrong
import com.paulscode.lightningfork.ui.theme.Danger
import com.paulscode.lightningfork.ui.theme.InputBg
import com.paulscode.lightningfork.ui.theme.Surface
import com.paulscode.lightningfork.ui.theme.SurfaceRaised
import com.paulscode.lightningfork.ui.theme.TextFaint
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.ui.theme.TextPrimary
import com.paulscode.lightningfork.ui.theme.Warning
import com.paulscode.lightningfork.util.Format

/** A screen's title row, with a back arrow when there is somewhere to go back to. */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextPrimary)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.weight(1f))
        actions()
    }
}

/** The dashboard's card: rounded, hairline-bordered surface. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: androidx.compose.ui.unit.Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Surface)
            .border(BorderStroke(1.dp, Border), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Two or more choices side by side, the selected one lit. */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(Surface)
            .border(BorderStroke(1.dp, Border), shape)
            .padding(4.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, label = "segment")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceRaised)
                .border(BorderStroke(1.dp, BorderStrong), RoundedCornerShape(12.dp)),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val color by animateColorAsState(if (i == selected) TextPrimary else TextMuted, label = "segText")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
        }
    }
}

/** A labelled text field in the dashboard's inset style. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
        }
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(InputBg)
                .border(BorderStroke(1.dp, BorderStrong), shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = textStyle, color = TextFaint)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    minLines = minLines,
                    enabled = enabled,
                    textStyle = textStyle.copy(color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/** An amount field, with the unit as a tap-to-switch suffix. */
@Composable
fun AmountField(
    text: String,
    onTextChange: (String) -> Unit,
    unit: AmountUnit,
    onToggleUnit: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = stringResource(R.string.common_amount),
    placeholder: String = "0",
    fiat: String? = null,
) {
    Column(modifier) {
        AppTextField(
            value = text,
            onValueChange = { raw ->
                // Sats: digits, with commas as grouping. BTC: digits and one
                // decimal point, which a comma (the decimal key on many
                // keyboards) also types, and no more than 8 places.
                val v = if (unit == AmountUnit.Btc) raw.replace(',', '.') else raw
                val ok = if (unit == AmountUnit.Sats) {
                    v.all { it.isDigit() || it == ',' }
                } else {
                    v.all { it.isDigit() || it == '.' } && v.count { it == '.' } <= 1 &&
                        v.substringAfter('.', "").length <= 8
                }
                if (ok && v.length <= 20) onTextChange(v)
            },
            label = label,
            placeholder = placeholder,
            keyboardType = if (unit == AmountUnit.Sats) KeyboardType.Number else KeyboardType.Decimal,
            textStyle = MaterialTheme.typography.headlineSmall,
            trailing = {
                Text(
                    Format.unitLabel(unit),
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceRaised)
                        .clickable(onClickLabel = stringResource(R.string.common_switch_unit), onClick = onToggleUnit)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            },
        )
        if (fiat != null) {
            Text(fiat, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        }
    }
}

/** A label and its value on one line, for review screens. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = TextPrimary, emphasize: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        // Both wrap rather than squeeze each other out at large font sizes.
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** A message the user needs to see, without taking over the screen. */
@Composable
fun Notice(text: String?, modifier: Modifier = Modifier, kind: NoticeKind = NoticeKind.Error) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val color = when (kind) {
            NoticeKind.Error -> Danger
            NoticeKind.Warning -> Warning
            NoticeKind.Info -> Accent
        }
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(color.copy(alpha = 0.12f))
                .border(BorderStroke(1.dp, color.copy(alpha = 0.35f)), shape)
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (kind == NoticeKind.Info) Icons.Rounded.Info else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
    }
}

enum class NoticeKind { Error, Warning, Info }

/** A small colored dot, for status. */
@Composable
fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** A pill-shaped label, as the dashboard's chain badge. */
@Composable
fun Pill(text: String, color: Color = Accent, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.32f)), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
