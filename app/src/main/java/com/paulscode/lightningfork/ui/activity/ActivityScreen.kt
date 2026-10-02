package com.paulscode.lightningfork.ui.activity

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.net.ActivityItem
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.NodeApi
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
import com.paulscode.lightningfork.util.Format
import kotlinx.coroutines.launch

/** The latest payments in and out, on-chain and over Lightning. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(api: NodeApi, unit: AmountUnit, onClose: () -> Unit) {
    var items by remember { mutableStateOf<List<ActivityItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            items = api.activity(60).items
            error = null
        } catch (e: ApiException) {
            error = e.message
        } catch (e: Exception) {
            error = "Can't reach your node right now."
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Activity", onBack = onClose)
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
                    Notice(error)
                    if (error == null) {
                        Text(
                            "Nothing yet. Payments you send and receive show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    if (error != null) item { Notice(error, modifier = Modifier.padding(bottom = 12.dp)) }
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
            val title = item.description.ifBlank {
                when {
                    incoming && item.kind == "onchain" -> "Received on-chain"
                    incoming -> "Received"
                    item.kind == "onchain" -> "Sent on-chain"
                    else -> "Sent"
                }
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Format.ago(item.timestamp), style = MaterialTheme.typography.bodySmall, color = TextFaint)
                when (item.status) {
                    "pending" -> Pill(if (item.kind == "onchain") "Confirming" else "Pending", color = Warning)
                    "failed" -> Pill("Failed", color = Danger)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (incoming) "+" else "−") + Format.amountWithUnit(item.amountSat, unit),
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    item.status == "failed" -> TextFaint
                    incoming -> Success
                    else -> TextPrimary
                },
            )
            if (!incoming && item.feeSat > 0) {
                Text("fee ${Format.amountWithUnit(item.feeSat, unit)}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
        }
    }
}
