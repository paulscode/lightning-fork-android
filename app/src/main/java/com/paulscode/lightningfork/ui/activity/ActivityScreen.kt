package com.paulscode.lightningfork.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.net.ActivityItem
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.NodeApi
import com.paulscode.lightningfork.ui.components.InfoRow
import com.paulscode.lightningfork.ui.components.Notice
import com.paulscode.lightningfork.ui.components.Pill
import com.paulscode.lightningfork.ui.components.TopBar
import com.paulscode.lightningfork.ui.home.BitcoinMark
import com.paulscode.lightningfork.ui.home.LightningMark
import com.paulscode.lightningfork.ui.theme.Accent
import com.paulscode.lightningfork.ui.theme.Border
import com.paulscode.lightningfork.ui.theme.Danger
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.Success
import com.paulscode.lightningfork.ui.theme.TextFaint
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.ui.theme.TextPrimary
import com.paulscode.lightningfork.ui.theme.Warning
import com.paulscode.lightningfork.ui.text.UiText
import com.paulscode.lightningfork.ui.text.text
import com.paulscode.lightningfork.ui.text.textOrNull
import com.paulscode.lightningfork.util.Clipboard
import com.paulscode.lightningfork.util.Coin
import com.paulscode.lightningfork.util.Format
import kotlinx.coroutines.launch

/** The latest payments in and out, on-chain and over Lightning. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(api: NodeApi, unit: AmountUnit, onClose: () -> Unit) {
    var items by remember { mutableStateOf<List<ActivityItem>?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            items = api.activity(60).items
            error = null
        } catch (e: ApiException) {
            error = e.message?.let(UiText::raw)
        } catch (e: Exception) {
            error = UiText.of(R.string.activityscreen_unreachable)
        }
    }
    LaunchedEffect(Unit) { load() }
    // A SHA256 invoice on its way is looked at again until it is not.
    val waiting = items?.let { ActivityLabels.anyOnItsWay(it) } == true
    LaunchedEffect(waiting) {
        while (waiting) {
            kotlinx.coroutines.delay(15_000)
            load()
        }
    }

    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().navigationBarsPadding()) {
        TopBar(stringResource(R.string.activityscreen_title), onBack = onClose)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    load()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            val list = items
            when {
                list == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
                list.isNullOrEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Notice(error.textOrNull())
                    if (error == null) {
                        Text(
                            stringResource(R.string.activityscreen_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    if (error != null) item { Notice(error.textOrNull(), modifier = Modifier.padding(bottom = 12.dp)) }
                    items(list, key = { it.id }) { item ->
                        ActivityRow(item, unit)
                        HorizontalDivider(color = Border)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, unit: AmountUnit) {
    val incoming = item.direction == "in"
    val bitcoin = item.bitcoinInvoice
    // A Bitcoin invoice opens to its details: what it paid, and the proof.
    var open by remember { mutableStateOf(false) }
    val clickLabel = stringResource(if (open) R.string.activityscreen_hide_details else R.string.activityscreen_show_details)
    val expandedState = stringResource(if (open) R.string.activityscreen_expanded else R.string.activityscreen_collapsed)
    Column(
        if (bitcoin != null) {
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = clickLabel) { open = !open }
                .semantics { stateDescription = expandedState }
        } else Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                if (item.kind == "onchain") BitcoinMark(40.dp) else LightningMark(40.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Page)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(if (incoming) Success else Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (incoming) Icons.Rounded.CallReceived else Icons.Rounded.CallMade,
                        contentDescription = null,
                        tint = Page,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ActivityLabels.title(item).text(), style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1)
                ActivityLabels.subtitle(item)?.let {
                    Text(it.text(), style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Format.ago(item.timestamp), style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    ActivityLabels.status(item)?.let { (text, tone) ->
                        Pill(
                            text.text(),
                            color = when (tone) {
                                StatusTone.Waiting -> Warning
                                StatusTone.Returned -> TextMuted
                                StatusTone.Failed -> Danger
                            },
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(
                        if (incoming) R.string.activityscreen_amount_in else R.string.activityscreen_amount_out,
                        // From the node's own bridge, nothing was spent here:
                        // what its SHA256 node paid.
                        if (bitcoin?.source == com.paulscode.lightningfork.net.OWN_BRIDGE) {
                            Format.amountWithUnit(bitcoin.amountSat, unit, Coin.Sha256)
                        } else {
                            Format.amountWithUnit(item.amountSat, unit, if (bitcoin != null) Coin.Btcb2 else null)
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        ActivityLabels.didNotMove(item) -> TextFaint
                        incoming -> Success
                        else -> TextPrimary
                    },
                )
                if (bitcoin?.source == com.paulscode.lightningfork.net.OWN_BRIDGE) {
                    Text(stringResource(R.string.activityscreen_from_your_bridge), style = MaterialTheme.typography.bodySmall, color = TextFaint)
                } else if (bitcoin != null) {
                    Text(stringResource(R.string.activityscreen_paid, Format.amountWithUnit(bitcoin.amountSat, unit, Coin.Sha256)), style = MaterialTheme.typography.bodySmall, color = TextFaint)
                } else if (!incoming && item.feeSat > 0) {
                    Text(stringResource(R.string.activityscreen_fee, Format.amountWithUnit(item.feeSat, unit)), style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
            }
        }
        if (bitcoin != null) {
            AnimatedVisibility(open) {
                BitcoinInvoiceDetails(item, unit)
            }
        }
    }
}

@Composable
private fun BitcoinInvoiceDetails(item: ActivityItem, unit: AmountUnit) {
    val bitcoin = item.bitcoinInvoice ?: return
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 54.dp, bottom = 12.dp)) {
        val about = bitcoin.description.ifBlank { item.description }
        if (about.isNotBlank()) InfoRow(stringResource(R.string.activityscreen_description), about)
        InfoRow(stringResource(R.string.activityscreen_paid_on_sha256), Format.amountWithUnit(bitcoin.amountSat, unit, Coin.Sha256))
        InfoRow(stringResource(R.string.activityscreen_cost), Format.amountWithUnit(item.amountSat, unit, Coin.Btcb2))
        if (item.feeSat > 0) InfoRow(stringResource(R.string.activityscreen_routing_fee), Format.amountWithUnit(item.feeSat, unit, Coin.Btcb2))
        InfoRow(stringResource(R.string.activityscreen_total), Format.amountWithUnit(item.amountSat + item.feeSat, unit, Coin.Btcb2), emphasize = true)
        if (bitcoin.serviceLabel.isNotBlank()) InfoRow(stringResource(R.string.activityscreen_service), bitcoin.serviceLabel)
        InfoRow(
            stringResource(R.string.activityscreen_status),
            when (bitcoin.state) {
                "paid" -> stringResource(R.string.activityscreen_status_paid)
                "pending" -> stringResource(R.string.activityscreen_status_on_its_way)
                "returned" -> stringResource(R.string.activityscreen_status_returned)
                else -> bitcoin.state
            },
        )
        val proof = item.preimage
        val clipLabel = stringResource(R.string.activityscreen_clip_preimage)
        if (!proof.isNullOrBlank()) {
            Row(
                Modifier.fillMaxWidth().clickable { Clipboard.copySensitive(context, proof, clipLabel) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.activityscreen_proof), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Spacer(Modifier.weight(1f))
                Text(Format.middle(proof, 8, 8), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = TextPrimary)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.activityscreen_copy), tint = Accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}
