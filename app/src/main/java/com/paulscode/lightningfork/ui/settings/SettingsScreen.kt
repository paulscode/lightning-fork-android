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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.ui.text.UiText
import com.paulscode.lightningfork.ui.text.text
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role

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
        TopBar(stringResource(R.string.settings_title), onBack = onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Section(stringResource(R.string.settings_section_node))
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                val node = wallet.node
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(node?.alias?.ifBlank { null } ?: stringResource(R.string.settings_node_fallback_alias), style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                    Pill(if (node?.network == "mainnet" || node == null) stringResource(R.string.settings_chain_blake2b) else node.network)
                }
                Spacer(Modifier.height(8.dp))
                if (node != null) {
                    InfoRow(stringResource(R.string.settings_block_height), Format.sats(node.blockHeight))
                    InfoRow(stringResource(R.string.settings_synced), if (node.syncedToChain) stringResource(R.string.settings_synced_yes) else stringResource(R.string.settings_synced_catching_up), valueColor = if (node.syncedToChain) Success else Warning)
                    InfoRow(stringResource(R.string.settings_channels), Format.sats(node.activeChannels.toLong()))
                    InfoRow(stringResource(R.string.settings_version), node.version.substringBefore(" "))
                }
            }

            Section(stringResource(R.string.settings_section_sha256))
            Sha256InvoicesCard(container, wallet)

            Section(stringResource(R.string.settings_section_connection))
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (route != null && wallet.error == null) Success else Warning)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (route) {
                            Route.Lan -> stringResource(R.string.settings_route_lan)
                            Route.Tor -> stringResource(R.string.settings_route_tor)
                            null -> stringResource(R.string.settings_route_none)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                endpoints.lanUrl?.let { InfoRow(stringResource(R.string.settings_local_address), it.removePrefix("https://")) }
                endpoints.lanIp?.let { InfoRow(stringResource(R.string.settings_local_ip), it.removePrefix("https://")) }
                endpoints.onionUrl?.let { InfoRow(stringResource(R.string.settings_tor_address), Format.middle(it.substringAfter("://"), 10, 12)) }
                // Without an onion address there is nothing for Tor to reach:
                // the app works at home only until the node has one.
                if (endpoints.onionUrl == null) {
                    InfoRow(stringResource(R.string.settings_away_from_home), stringResource(R.string.settings_not_set_up), valueColor = Warning)
                    Text(
                        stringResource(R.string.settings_no_onion),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                } else InfoRow(
                    stringResource(R.string.settings_tor),
                    when (torStatus) {
                        TorStatus.Stopped -> stringResource(R.string.settings_tor_stopped)
                        TorStatus.Bootstrapping -> stringResource(R.string.settings_tor_starting, Format.percent(torProgress / 100.0))
                        TorStatus.Ready -> stringResource(R.string.settings_tor_ready)
                        TorStatus.Failed -> stringResource(R.string.settings_tor_failed)
                    },
                    valueColor = if (torStatus == TorStatus.Failed) Danger else TextPrimary,
                )
                endpoints.caSha256?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.settings_pinned_certificate), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Text(
                        it.take(47) + "…",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = TextFaint,
                    )
                }
            }

            Section(stringResource(R.string.settings_section_display))
            SegmentedToggle(
                options = listOf(Format.unitLabel(AmountUnit.Sats), Format.unitLabel(AmountUnit.Btc)),
                selected = if (unit == AmountUnit.Sats) 0 else 1,
                onSelect = { onUnit(if (it == 0) AmountUnit.Sats else AmountUnit.Btc) },
            )
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                stringResource(R.string.settings_show_estimates),
                if (showFiat && wallet.fiatPrice != null) stringResource(R.string.settings_show_estimates_in, currencyName(wallet.fiatCurrency))
                else stringResource(R.string.settings_show_estimates_source),
                showFiat,
            ) {
                showFiat = it
                container.wallet.setShowFiat(it)
            }
            if (showFiat) EstimatesCurrencyCard(container, wallet)

            Section(stringResource(R.string.settings_section_security))
            ToggleRow(
                stringResource(R.string.settings_lock_app),
                if (lockAvailable) stringResource(R.string.settings_lock_app_on_open) else stringResource(R.string.settings_lock_app_unavailable),
                appLock && lockAvailable,
                enabled = lockAvailable,
            ) {
                appLock = it
                settings.appLock = it
            }

            Section(stringResource(R.string.settings_section_phone))
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                InfoRow(stringResource(R.string.settings_name_on_node), settings.deviceLabel ?: "—")
                Text(
                    stringResource(R.string.settings_remove_phone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton(stringResource(R.string.settings_unpair_phone), onClick = { confirmUnpair = true }, contentColor = Danger, modifier = Modifier.fillMaxWidth())

            Section(stringResource(R.string.settings_section_about))
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                InfoRow(stringResource(R.string.settings_app_version), BuildConfig.VERSION_NAME)
                com.paulscode.lightningfork.ui.components.QuietButton(
                    stringResource(R.string.settings_licenses),
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
            title = { Text(stringResource(R.string.settings_unpair_title)) },
            text = {
                Text(stringResource(R.string.settings_unpair_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    container.unpairAndRemove()
                    onUnpaired()
                }) { Text(stringResource(R.string.settings_unpair), color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.settings_cancel), color = Accent) }
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
    var error by remember { mutableStateOf<UiText?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (explain) com.paulscode.lightningfork.ui.send.Sha256Explainer(onDismiss = { explain = false })
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        val summary = dashboard?.bitcoinInvoices
        when {
            dashboard == null -> Text(stringResource(R.string.settings_asking_node), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            !dashboard.paysSha256Invoices -> Text(
                stringResource(R.string.settings_sha256_dashboard_too_old),
                style = MaterialTheme.typography.bodySmall,
                color = Warning,
            )
            summary == null || !summary.configured -> Text(
                stringResource(R.string.settings_sha256_not_set_up),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            else -> {
                InfoRow(stringResource(R.string.settings_service), summary.label.ifBlank { stringResource(R.string.settings_service_set_up) })
                InfoRow(stringResource(R.string.settings_allowed_above_market), Format.percent(summary.premium))
                if (summary.onion) InfoRow(stringResource(R.string.settings_reached), stringResource(R.string.settings_over_tor))
                val st = status
                if (st != null) {
                    val terms = st.terms
                    when {
                        st.error != null -> InfoRow(stringResource(R.string.settings_right_now), stringResource(R.string.settings_service_not_answering), valueColor = Warning)
                        terms == null -> InfoRow(stringResource(R.string.settings_right_now), stringResource(R.string.settings_service_no_sha256), valueColor = Warning)
                        !terms.open -> InfoRow(stringResource(R.string.settings_right_now), terms.refusal ?: stringResource(R.string.settings_service_not_paying), valueColor = Warning)
                        else -> {
                            InfoRow(stringResource(R.string.settings_right_now), stringResource(R.string.settings_service_paying), valueColor = Success)
                            if (terms.rate > 0) InfoRow(stringResource(R.string.settings_rate), stringResource(R.string.settings_rate_value, Format.inverseRate(terms.rate)))
                            InfoRow(stringResource(R.string.settings_fee), Format.percent(terms.spread))
                            if (terms.maxSat > 0) InfoRow(stringResource(R.string.settings_pays), pluralStringResource(R.plurals.settings_pays_range, terms.maxSat.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), Format.sats(terms.minSat), Format.sats(terms.maxSat)))
                        }
                    }
                    st.reference?.let { ref ->
                        if (ref.rate > 0) InfoRow(
                            stringResource(R.string.settings_market),
                            if (ref.source.isNotBlank()) stringResource(R.string.settings_market_value_source, Format.inverseRate(ref.rate), ref.source)
                            else stringResource(R.string.settings_market_value, Format.inverseRate(ref.rate)),
                        )
                    }
                }
                error?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall, color = Warning) }
                com.paulscode.lightningfork.ui.components.QuietButton(
                    if (loading) stringResource(R.string.settings_asking_service) else if (status == null) stringResource(R.string.settings_check_terms) else stringResource(R.string.settings_check_again),
                    onClick = {
                        if (loading) return@QuietButton
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                status = container.api.bitcoinInvoices()
                            } catch (e: com.paulscode.lightningfork.net.ApiException) {
                                error = UiText.raw(e.message)
                            } catch (e: Exception) {
                                error = UiText.of(R.string.settings_cant_reach_node)
                            }
                            loading = false
                        }
                    },
                )
                Text(
                    stringResource(R.string.settings_sha256_changed_in_dashboard),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                )
            }
        }
        com.paulscode.lightningfork.ui.components.QuietButton(stringResource(R.string.settings_sha256_how_it_works), onClick = { explain = true })
    }
}

/** The phone's own currency, by its region; null where it has none. */
private fun phoneCurrency(): String? =
    runCatching { java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode }.getOrNull()

/** "Euro (EUR)" in the phone's language; the code alone where the phone has no name for it. */
@Composable
private fun currencyName(code: String): String {
    val name = runCatching { java.util.Currency.getInstance(code).getDisplayName(java.util.Locale.getDefault()) }.getOrNull()
    return if (name.isNullOrBlank() || name.equals(code, ignoreCase = true)) code
    else stringResource(R.string.settings_currency_named, name, code)
}

/**
 * Which currency estimates are in: the phone's own (when the node quotes
 * it) or one the node quotes, chosen from a list asked of the node when
 * opened. Without a quote in the chosen one the wallet shows dollars, and
 * says so here.
 */
@Composable
private fun EstimatesCurrencyCard(container: AppContainer, wallet: WalletState) {
    var chosen by remember { mutableStateOf(container.settings.fiatCurrency) }
    var choosing by remember { mutableStateOf(false) }
    val phone = remember { phoneCurrency() }
    val wanted = chosen ?: phone ?: "USD"
    Spacer(Modifier.height(6.dp))
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        InfoRow(
            stringResource(R.string.settings_currency),
            chosen?.let { currencyName(it) } ?: stringResource(R.string.settings_currency_phone),
        )
        if (wallet.fiatPrice != null && !wallet.fiatCurrency.equals(wanted, ignoreCase = true)) {
            Text(
                stringResource(R.string.settings_currency_fallback, currencyName(wallet.fiatCurrency), currencyName(wanted)),
                style = MaterialTheme.typography.bodySmall,
                color = Warning,
            )
        }
        com.paulscode.lightningfork.ui.components.QuietButton(
            stringResource(R.string.settings_currency_change),
            onClick = { choosing = true },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (choosing) {
        CurrencyDialog(
            container = container,
            chosen = chosen,
            phone = phone,
            onChoose = {
                choosing = false
                if (it != chosen) {
                    chosen = it
                    container.wallet.setFiatCurrency(it)
                }
            },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun CurrencyDialog(
    container: AppContainer,
    chosen: String?,
    phone: String?,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var codes by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    LaunchedEffect(Unit) {
        try {
            codes = container.api.currencies().map { it.uppercase() }.distinct()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.paulscode.lightningfork.net.ApiException) {
            error = UiText.raw(e.message)
        } catch (e: Exception) {
            error = UiText.of(R.string.settings_cant_reach_node)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_currency_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChoiceRow(
                    stringResource(R.string.settings_currency_phone),
                    phone?.let { currencyName(it) },
                    selected = chosen == null,
                ) { onChoose(null) }
                val list = codes
                val err = error
                when {
                    list == null && err == null -> Text(stringResource(R.string.settings_asking_node), style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(top = 8.dp))
                    list == null -> Text(err!!.text(), style = MaterialTheme.typography.bodySmall, color = Warning, modifier = Modifier.padding(top = 8.dp))
                    else -> {
                        // A choice the node no longer quotes stays listed, so it can be seen and changed.
                        val all = if (chosen != null && chosen !in list) listOf(chosen) + list else list
                        if (all.isEmpty()) Text(stringResource(R.string.settings_currency_none), style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(top = 8.dp))
                        all.forEach { code -> ChoiceRow(currencyName(code), null, selected = chosen == code) { onChoose(code) } }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel), color = Accent) }
        },
    )
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Accent))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted) }
        }
    }
}
