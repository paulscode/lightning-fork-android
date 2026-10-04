package com.paulscode.lightningfork.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.paulscode.lightningfork.BuildConfig
import com.paulscode.lightningfork.AppContainer
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.net.Route
import com.paulscode.lightningfork.net.TorStatus
import com.paulscode.lightningfork.ui.components.AppCard
import com.paulscode.lightningfork.ui.components.Dot
import com.paulscode.lightningfork.ui.components.InfoRow
import com.paulscode.lightningfork.ui.components.Pill
import com.paulscode.lightningfork.ui.components.SecondaryButton
import com.paulscode.lightningfork.ui.components.SegmentedToggle
import com.paulscode.lightningfork.ui.components.TopBar
import com.paulscode.lightningfork.ui.theme.Accent
import com.paulscode.lightningfork.ui.theme.Danger
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.Success
import com.paulscode.lightningfork.ui.theme.TextFaint
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.ui.theme.TextPrimary
import com.paulscode.lightningfork.ui.theme.Warning
import com.paulscode.lightningfork.util.Format
import com.paulscode.lightningfork.wallet.WalletState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    wallet: WalletState,
    unit: AmountUnit,
    onUnit: (AmountUnit) -> Unit,
    lockAvailable: Boolean,
    onClose: () -> Unit,
    onLicenses: () -> Unit = {},
    onUnpaired: () -> Unit,
) {
    val settings = container.settings
    var showFiat by remember { mutableStateOf(settings.showFiat) }
    var appLock by remember { mutableStateOf(settings.appLock) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val route by container.transport.route.collectAsState()
    val torStatus by container.tor.status.collectAsState()
    val torProgress by container.tor.progress.collectAsState()
    val endpoints = settings.endpoints

    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Settings", onBack = onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Section("Your node")
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                val node = wallet.node
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(node?.alias?.ifBlank { null } ?: "Lightning Fork node", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                    Pill(if (node?.network == "mainnet" || node == null) "BLAKE2b chain" else node.network)
                }
                Spacer(Modifier.height(8.dp))
                if (node != null) {
                    InfoRow("Block height", Format.sats(node.blockHeight))
                    InfoRow("Synced", if (node.syncedToChain) "Yes" else "Catching up", valueColor = if (node.syncedToChain) Success else Warning)
                    InfoRow("Channels", node.activeChannels.toString())
                    InfoRow("Version", node.version.substringBefore(" "))
                }
            }

            Section("Paying SHA256 invoices")
            Sha256InvoicesCard(container, wallet)

            Section("Connection")
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (route != null && wallet.error == null) Success else Warning)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (route) {
                            Route.Lan -> "On your local network"
                            Route.Tor -> "Over Tor"
                            null -> "Not connected yet"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                endpoints.lanUrl?.let { InfoRow("Local address", it.removePrefix("https://")) }
                endpoints.lanIp?.let { InfoRow("Local IP", it.removePrefix("https://")) }
                endpoints.onionUrl?.let { InfoRow("Tor address", Format.middle(it.substringAfter("://"), 10, 12)) }
                // Without an onion address there is nothing for Tor to reach:
                // the app works at home only until the node has one.
                if (endpoints.onionUrl == null) {
                    InfoRow("Away from home", "Not set up", valueColor = Warning)
                    Text(
                        "Your node has no onion address yet, so the app reaches it only on your local network. " +
                            "The dashboard's Mobile app screen shows how to add one; the app picks it up the next time it connects at home.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                } else InfoRow(
                    "Tor",
                    when (torStatus) {
                        TorStatus.Stopped -> "Starts when needed"
                        TorStatus.Bootstrapping -> "Starting… $torProgress%"
                        TorStatus.Ready -> "Ready"
                        TorStatus.Failed -> "Could not start"
                    },
                    valueColor = if (torStatus == TorStatus.Failed) Danger else TextPrimary,
                )
                endpoints.caSha256?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Pinned certificate", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Text(
                        it.take(47) + "…",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = TextFaint,
                    )
                }
            }

            Section("Display")
            SegmentedToggle(
                options = listOf("sats", "BTC"),
                selected = if (unit == AmountUnit.Sats) 0 else 1,
                onSelect = { onUnit(if (it == 0) AmountUnit.Sats else AmountUnit.Btc) },
            )
            Spacer(Modifier.height(10.dp))
            ToggleRow("Show US dollar estimates", "From the dashboard's price source", showFiat) {
                showFiat = it
                container.wallet.setShowFiat(it)
            }

            Section("Security")
            ToggleRow(
                "Lock the app",
                if (lockAvailable) "Ask for your fingerprint, face or screen lock when opening" else "Set a screen lock on this phone to use this",
                appLock && lockAvailable,
                enabled = lockAvailable,
            ) {
                appLock = it
                settings.appLock = it
            }

            Section("This phone")
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                InfoRow("Name on your node", settings.deviceLabel ?: "—")
                Text(
                    "Remove this phone from your node in the dashboard (menu → Mobile app), or unpair it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Unpair this phone", onClick = { confirmUnpair = true }, contentColor = Danger, modifier = Modifier.fillMaxWidth())

            Section("About")
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                InfoRow("App version", BuildConfig.VERSION_NAME)
                com.paulscode.lightningfork.ui.components.QuietButton(
                    "Open-source licenses",
                    onClick = onLicenses,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = {
                Text("The phone forgets your node and its key, and asks your node to remove it. Your funds stay on your node. To use the app again, pair it from the dashboard.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    container.unpairAndRemove()
                    onUnpaired()
                }) { Text("Unpair", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("Cancel", color = Accent) }
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextFaint,
        modifier = Modifier.padding(start = 4.dp, top = 26.dp, bottom = 10.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) TextPrimary else TextMuted)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
}

/**
 * Whether this node can pay SHA256 invoices, through which service, at what
 * premium; and, asked for, the service's terms now. Set up only in the
 * dashboard: a service code carries a credential.
 */
@Composable
private fun Sha256InvoicesCard(container: AppContainer, wallet: WalletState) {
    val dashboard = wallet.dashboard
    var explain by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<com.paulscode.lightningfork.net.BitcoinInvoicesStatus?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (explain) com.paulscode.lightningfork.ui.send.Sha256Explainer(onDismiss = { explain = false })
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        val summary = dashboard?.bitcoinInvoices
        when {
            dashboard == null -> Text("Asking your node…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            !dashboard.paysSha256Invoices -> Text(
                "Your dashboard is too old to pay SHA256 invoices. Update Lightning Fork on your node to use them.",
                style = MaterialTheme.typography.bodySmall,
                color = Warning,
            )
            summary == null || !summary.configured -> Text(
                "Not set up. To pay SHA256 invoices from this phone, add a service in the dashboard, under Paying SHA256 invoices, with the code its operator gives you.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            else -> {
                InfoRow("Service", summary.label.ifBlank { "Set up" })
                InfoRow("Allowed above the market", Format.percent(summary.premium))
                if (summary.onion) InfoRow("Reached", "Over Tor")
                val st = status
                if (st != null) {
                    val terms = st.terms
                    when {
                        st.error != null -> InfoRow("Right now", "Not answering", valueColor = Warning)
                        terms == null -> InfoRow("Right now", "Doesn't pay SHA256 invoices", valueColor = Warning)
                        !terms.open -> InfoRow("Right now", terms.refusal ?: "Not paying", valueColor = Warning)
                        else -> {
                            InfoRow("Right now", "Paying", valueColor = Success)
                            if (terms.rate > 0) InfoRow("Rate", "1 BTC (SHA256) ≈ ${Format.inverseRate(terms.rate)} BTCB2")
                            InfoRow("Fee", Format.percent(terms.spread))
                            if (terms.maxSat > 0) InfoRow("Pays", "${Format.sats(terms.minSat)} to ${Format.sats(terms.maxSat)} sats (SHA256)")
                        }
                    }
                    st.reference?.let { ref ->
                        if (ref.rate > 0) InfoRow("Market", "1 BTC (SHA256) ≈ ${Format.inverseRate(ref.rate)} BTCB2${if (ref.source.isNotBlank()) ", ${ref.source}" else ""}")
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warning) }
                com.paulscode.lightningfork.ui.components.QuietButton(
                    if (loading) "Asking the service…" else if (status == null) "Check its terms now" else "Check again",
                    onClick = {
                        if (loading) return@QuietButton
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                status = container.api.bitcoinInvoices()
                            } catch (e: com.paulscode.lightningfork.net.ApiException) {
                                error = e.message
                            } catch (e: Exception) {
                                error = "Can't reach your node right now."
                            }
                            loading = false
                        }
                    },
                )
                Text(
                    "Changed only in the dashboard, under Paying SHA256 invoices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                )
            }
        }
        com.paulscode.lightningfork.ui.components.QuietButton("How paying a SHA256 invoice works", onClick = { explain = true })
    }
}
