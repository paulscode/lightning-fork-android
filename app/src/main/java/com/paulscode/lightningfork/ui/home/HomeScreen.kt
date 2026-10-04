package com.paulscode.lightningfork.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.ui.components.AnimatedAmount
import com.paulscode.lightningfork.ui.components.Dot
import com.paulscode.lightningfork.ui.components.Notice
import com.paulscode.lightningfork.ui.components.NoticeKind
import com.paulscode.lightningfork.ui.components.Pill
import com.paulscode.lightningfork.ui.components.PrimaryButton
import com.paulscode.lightningfork.ui.components.SecondaryButton
import com.paulscode.lightningfork.ui.theme.Accent
import com.paulscode.lightningfork.ui.theme.BitcoinGradient
import com.paulscode.lightningfork.ui.theme.Border
import com.paulscode.lightningfork.ui.theme.LightningGradient
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.Success
import com.paulscode.lightningfork.ui.theme.Surface
import com.paulscode.lightningfork.ui.theme.TextFaint
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.ui.theme.TextPrimary
import com.paulscode.lightningfork.ui.theme.Warning
import com.paulscode.lightningfork.util.Format
import com.paulscode.lightningfork.wallet.Connection
import com.paulscode.lightningfork.wallet.WalletState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: WalletState,
    unit: AmountUnit,
    onToggleUnit: () -> Unit,
    onRefresh: suspend () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onActivity: () -> Unit,
    onSettings: () -> Unit,
    torStarting: Int? = null,
    pendingSend: com.paulscode.lightningfork.net.PendingSend? = null,
    onCheckPending: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var pulling by remember { mutableStateOf(false) }
    // Ticks so "updated 2 min ago" stays true without a refresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    Box(Modifier.fillMaxSize().background(Page)) {
        StormBackdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Header(state, onActivity, onSettings, torStarting)
            PullToRefreshBox(
                isRefreshing = pulling,
                onRefresh = {
                    scope.launch {
                        pulling = true
                        onRefresh()
                        pulling = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Spacer(Modifier.height(12.dp))
                    val stale = state.updatedAtMs > 0 && now - state.updatedAtMs > 90_000
                    Notice(
                        when {
                            state.error != null && state.wallet != null && state.updatedAtMs > 0 ->
                                "${state.error} Showing balances from ${Format.ago(state.updatedAtMs / 1000, now)}."
                            state.error != null -> state.error
                            else -> null
                        },
                        kind = NoticeKind.Warning,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                    if (pendingSend != null) {
                        PendingSendCard(pendingSend, unit, onCheckPending)
                        Spacer(Modifier.height(14.dp))
                    }
                    val w = state.wallet
                    BalanceCard(
                        title = "On-chain",
                        caption = "Confirmed",
                        mark = { BitcoinMark() },
                        sats = w?.onchain?.confirmedSat,
                        unit = unit,
                        onToggleUnit = onToggleUnit,
                        fiat = w?.onchain?.confirmedSat?.let { Format.fiat(it, state.usdPrice) },
                        lines = buildList {
                            val incoming = w?.onchain?.unconfirmedSat ?: 0
                            if (incoming > 0) add("${Format.amountWithUnit(incoming, unit)} unconfirmed" to Warning)
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                    BalanceCard(
                        title = "Lightning",
                        caption = "Ready to send",
                        mark = { LightningMark() },
                        sats = w?.lightning?.outboundSat,
                        unit = unit,
                        onToggleUnit = onToggleUnit,
                        fiat = w?.lightning?.outboundSat?.let { Format.fiat(it, state.usdPrice) },
                        lines = buildList {
                            val opening = w?.lightning?.pendingOutboundSat ?: 0
                            if (opening > 0) add("${Format.amountWithUnit(opening, unit)} in channels opening" to Warning)
                            w?.lightning?.inboundSat?.let {
                                add("Can receive ${Format.amountWithUnit(it, unit)}" to TextMuted)
                            }
                        },
                    )
                    AnimatedVisibility(stale && state.error == null, enter = fadeIn(), exit = fadeOut()) {
                        Text(
                            "Updated ${Format.ago(state.updatedAtMs / 1000, now)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextFaint,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    if (w != null && !w.syncedToChain) {
                        Notice(
                            "Your node is still catching up with the chain; balances may be behind.",
                            kind = NoticeKind.Info,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SecondaryButton(
                    "Receive",
                    onClick = onReceive,
                    icon = Icons.Rounded.CallReceived,
                    modifier = Modifier.weight(1f),
                    height = 76.dp,
                )
                PrimaryButton(
                    "Send",
                    onClick = onSend,
                    icon = Icons.Rounded.CallMade,
                    modifier = Modifier.weight(1f),
                    height = 76.dp,
                )
            }
        }
    }
}

/** The dashboard's storm, faded into the page behind the header and balances. */
@Composable
private fun StormBackdrop() {
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        Image(
            painterResource(R.drawable.storm),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize().alpha(0.42f),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Page.copy(alpha = 0.35f),
                        0.55f to Page.copy(alpha = 0.75f),
                        1f to Page,
                    )
                )
        )
    }
}

@Composable
private fun Header(state: WalletState, onActivity: () -> Unit, onSettings: () -> Unit, torStarting: Int?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(LightningGradient),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_bolt),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Lightning Fork", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(state.connection, state.refreshing)
                Spacer(Modifier.width(6.dp))
                val where = when (state.connection) {
                    Connection.Lan -> "Local network"
                    Connection.Tor -> "Tor"
                    Connection.Connecting, Connection.Offline ->
                        if (torStarting != null) "Starting Tor… $torStarting%"
                        else if (state.connection == Connection.Connecting) "Connecting…" else "Not connected"
                }
                val alias = state.node?.alias?.takeIf { it.isNotBlank() }
                Text(
                    listOfNotNull(alias, where).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onActivity) {
            Icon(Icons.Rounded.History, contentDescription = "Activity", tint = TextPrimary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = TextPrimary)
        }
    }
}

@Composable
private fun ConnectionDot(connection: Connection, refreshing: Boolean) {
    val target = when (connection) {
        Connection.Lan, Connection.Tor -> Success
        Connection.Connecting -> Warning
        Connection.Offline -> Color(0xFFF46E6E)
    }
    val color by animateColorAsState(target, label = "conn")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (refreshing || connection == Connection.Connecting) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(Modifier.alpha(a)) { Dot(color, 7.dp) }
}

@Composable
private fun BalanceCard(
    title: String,
    caption: String,
    mark: @Composable () -> Unit,
    sats: Long?,
    unit: AmountUnit,
    onToggleUnit: () -> Unit,
    fiat: String?,
    lines: List<Pair<String, Color>>,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface.copy(alpha = 0.86f))
            .border(BorderStroke(1.dp, Border), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Switch between sats and BTC",
                onClick = onToggleUnit,
            )
            .padding(20.dp),
    ) {
        // The pill moves under the title when they don't fit side by side
        // (large font sizes), instead of the title breaking mid-word.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                mark()
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, softWrap = false)
            }
            Box(Modifier.align(Alignment.CenterVertically)) { Pill(caption, color = Accent) }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.BottomStart) {
            if (sats == null) {
                Text("—", style = MaterialTheme.typography.displayMedium, color = TextFaint)
            } else {
                AnimatedAmount(
                    sats = sats,
                    unit = unit,
                    style = MaterialTheme.typography.displayMedium,
                    unitStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                )
            }
        }
        if (fiat != null) {
            Text(fiat, style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.padding(top = 4.dp))
        }
        lines.forEach { (text, color) ->
            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = color, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun BitcoinMark(size: androidx.compose.ui.unit.Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(BitcoinGradient),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_bitcoin),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
fun LightningMark(size: androidx.compose.ui.unit.Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(LightningGradient),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_bolt),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** A send whose outcome the app never heard, with the way to ask. */
@Composable
private fun PendingSendCard(p: com.paulscode.lightningfork.net.PendingSend, unit: AmountUnit, onCheck: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Warning.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, Warning.copy(alpha = 0.35f)), shape)
            .clickable(onClickLabel = "Check this payment", onClick = onCheck)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("A payment didn't finish", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(
                if (p.bitcoinInvoice != null) {
                    "A SHA256 invoice, for at most ${Format.amountWithUnit(p.amountSat, unit, com.paulscode.lightningfork.util.Coin.Btcb2)}${ageOf(p)}. Check whether it went through."
                } else {
                    "${Format.amountWithUnit(p.amountSat, unit)} ${if (p.lightning) "over Lightning" else "on-chain"}. Check whether it went through."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("Check", style = MaterialTheme.typography.labelLarge, color = Accent)
    }
}

/** When an unfinished payment started, if not today: ", from 3 d ago". */
private fun ageOf(p: com.paulscode.lightningfork.net.PendingSend): String {
    if (p.startedAtMs <= 0) return ""
    val ageMs = System.currentTimeMillis() - p.startedAtMs
    return if (ageMs < 12 * 3600_000L) "" else ", from ${Format.ago(p.startedAtMs / 1000)}"
}
