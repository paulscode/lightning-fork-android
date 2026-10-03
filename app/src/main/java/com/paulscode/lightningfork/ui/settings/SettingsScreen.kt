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
                    Pill(if (node?.network == "mainnet" || node == null) "Bitcoin BLAKE2b chain" else node.network)
                }
                Spacer(Modifier.height(8.dp))
                if (node != null) {
                    InfoRow("Block height", Format.sats(node.blockHeight))
                    InfoRow("Synced", if (node.syncedToChain) "Yes" else "Catching up", valueColor = if (node.syncedToChain) Success else Warning)
                    InfoRow("Channels", node.activeChannels.toString())
                    InfoRow("Version", node.version.substringBefore(" "))
                }
                Text(
                    "Paying SHA256 invoices is set up in the dashboard's settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

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
                InfoRow(
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
