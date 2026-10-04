package com.paulscode.lightningfork.ui.send

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.net.PaymentTarget
import com.paulscode.lightningfork.ui.components.AmountField
import com.paulscode.lightningfork.ui.components.AnimatedAmount
import com.paulscode.lightningfork.ui.components.AppCard
import com.paulscode.lightningfork.ui.components.AppTextField
import com.paulscode.lightningfork.ui.components.InfoRow
import com.paulscode.lightningfork.ui.components.Notice
import com.paulscode.lightningfork.ui.components.NoticeKind
import com.paulscode.lightningfork.ui.components.Pill
import com.paulscode.lightningfork.ui.components.PrimaryButton
import com.paulscode.lightningfork.ui.components.SecondaryButton
import com.paulscode.lightningfork.ui.components.SegmentedToggle
import com.paulscode.lightningfork.ui.components.TopBar
import com.paulscode.lightningfork.ui.home.BitcoinMark
import com.paulscode.lightningfork.ui.home.LightningMark
import com.paulscode.lightningfork.ui.scan.ScanScreen
import com.paulscode.lightningfork.ui.theme.Accent
import com.paulscode.lightningfork.ui.theme.Border
import com.paulscode.lightningfork.ui.theme.BorderStrong
import com.paulscode.lightningfork.ui.theme.Danger
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.Success
import com.paulscode.lightningfork.ui.theme.Surface
import com.paulscode.lightningfork.ui.theme.SurfaceRaised
import com.paulscode.lightningfork.ui.theme.TextFaint
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.ui.theme.TextPrimary
import com.paulscode.lightningfork.ui.text.text
import com.paulscode.lightningfork.ui.text.textOrNull
import com.paulscode.lightningfork.ui.theme.Warning
import com.paulscode.lightningfork.util.Clipboard
import com.paulscode.lightningfork.util.Coin
import com.paulscode.lightningfork.util.Format
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import com.paulscode.lightningfork.wallet.WalletState
import kotlinx.coroutines.delay

@Composable
fun SendScreen(
    vm: SendViewModel,
    wallet: WalletState,
    startScanning: Boolean,
    onClose: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scanning by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startScanning) }

    fun paste() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotBlank()) {
            scanning = false
            vm.onInput(text)
            vm.submit(text)
        }
    }

    if (scanning) {
        BackHandler { scanning = false }
        ScanScreen(
            title = stringResource(R.string.send_scan_title),
            hint = stringResource(R.string.send_scan_hint),
            onResult = { text ->
                scanning = false
                vm.onScanned(text)
            },
            onPaste = { paste() },
            onClose = { scanning = false },
        )
        return
    }

    BackHandler {
        when (ui.step) {
            SendStep.Review -> vm.backToInput()
            // An uncertain send is not edited and resent: close, or check again.
            // Checked from Home and refused, there is no review to go back to.
            SendStep.Failed -> if (ui.uncertain || ui.target == null) onClose() else vm.backToReview()
            SendStep.Sending -> Unit
            else -> onClose()
        }
    }

    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AnimatedContent(
            targetState = ui.step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "sendStep",
            modifier = Modifier.weight(1f),
        ) { step ->
            when (step) {
                SendStep.Input -> InputStep(ui, vm, onClose, onPaste = ::paste, onScan = { scanning = true })
                SendStep.Review -> ReviewStep(ui, vm, wallet)
                SendStep.Sending -> SendingStep(ui, onClose)
                SendStep.Done -> DoneStep(ui, onClose)
                SendStep.Failed -> FailedStep(ui, vm, onClose)
            }
        }
    }
}

@Composable
private fun InputStep(ui: SendUi, vm: SendViewModel, onClose: () -> Unit, onPaste: () -> Unit, onScan: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.send_title), onBack = onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.send_input_heading), style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.send_input_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigChoice(stringResource(R.string.send_scan), stringResource(R.string.send_scan_subtitle), Icons.Rounded.QrCodeScanner, onScan, Modifier.weight(1f))
                BigChoice(stringResource(R.string.send_paste), stringResource(R.string.send_paste_subtitle), Icons.Rounded.ContentPaste, onPaste, Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
            AppTextField(
                value = ui.input,
                onValueChange = vm::onInput,
                label = stringResource(R.string.send_input_label),
                placeholder = stringResource(R.string.send_input_placeholder),
                singleLine = false,
                minLines = 3,
                imeAction = ImeAction.Done,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(14.dp))
            Notice(ui.inputError.textOrNull())
        }
        PrimaryButton(
            stringResource(R.string.send_continue),
            onClick = { vm.submit() },
            enabled = ui.input.isNotBlank(),
            loading = ui.decoding,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
    }
}

@Composable
private fun BigChoice(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(Surface)
            .border(BorderStroke(1.dp, BorderStrong), shape)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Accent)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
}

private fun kindTitle(t: PaymentTarget): Int = when (t.kind) {
    "onchain" -> R.string.send_kind_onchain
    "bolt11" -> R.string.send_kind_bolt11
    "offer" -> R.string.send_kind_offer
    "bolt12-invoice" -> R.string.send_kind_bolt12_invoice
    "bitcoin-invoice" -> R.string.send_kind_bolt11
    else -> R.string.send_kind_payment
}

@Composable
private fun ReviewStep(ui: SendUi, vm: SendViewModel, wallet: WalletState) {
    val t = ui.active ?: return
    val target = ui.target!!
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.send_review_title), onBack = vm::backToInput)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            if (target.fallback != null && target.isLightning) {
                SegmentedToggle(
                    options = listOf(stringResource(R.string.send_option_lightning), stringResource(R.string.send_option_onchain)),
                    selected = if (ui.payOnchain) 1 else 0,
                    onSelect = { vm.choosePayOnchain(it == 1) },
                )
                Spacer(Modifier.height(16.dp))
            }
            Notice(ui.reviewNotice.textOrNull(), kind = NoticeKind.Warning, modifier = Modifier.padding(bottom = 16.dp))
            // Why a SHA256 invoice can't be paid now, first, where it is seen.
            if (t.isBitcoinInvoice && (!t.payable || t.estimate == null)) {
                Notice(
                    t.message ?: stringResource(R.string.send_sha256_cant_pay_now),
                    kind = NoticeKind.Warning,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            RecipientCard(t)
            Spacer(Modifier.height(18.dp))
            if (t.isBitcoinInvoice) {
                BitcoinInvoiceReview(ui, t, wallet)
            } else if (t.amountEditable && !(ui.onchain && ui.sendAll)) {
                AmountField(
                    text = ui.amountText,
                    onTextChange = vm::onAmountText,
                    unit = ui.unit,
                    onToggleUnit = vm::toggleUnit,
                    fiat = Format.parseAmount(ui.amountText, ui.unit)?.let { Format.fiat(it, wallet.fiatPrice, wallet.fiatCurrency) },
                )
            } else {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.send_amount), style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Spacer(Modifier.height(6.dp))
                    val amount = ui.amountSat
                    if (amount != null) {
                        AnimatedAmount(amount, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary)
                        Format.fiat(amount, wallet.fiatPrice, wallet.fiatCurrency)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    } else {
                        Text(stringResource(R.string.send_working_it_out), style = MaterialTheme.typography.titleMedium, color = TextFaint)
                    }
                }
            }
            if (ui.onchain && t.amountEditable) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.send_everything), style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            stringResource(R.string.send_everything_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    Switch(
                        checked = ui.sendAll,
                        onCheckedChange = vm::onSendAll,
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                    )
                }
            }
            if (t.kind == "offer") {
                Spacer(Modifier.height(16.dp))
                AppTextField(
                    value = ui.payerNote,
                    onValueChange = vm::onPayerNote,
                    label = stringResource(R.string.send_payer_note_label),
                    placeholder = stringResource(R.string.send_payer_note_placeholder),
                )
            }
            Spacer(Modifier.height(18.dp))
            if (t.isBitcoinInvoice) {
                // Its costs are with the amount, above.
            } else if (ui.onchain) {
                FeeSelector(ui, vm)
                Spacer(Modifier.height(14.dp))
                AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    val est = ui.estimate
                    InfoRow(stringResource(R.string.send_amount), ui.amountSat?.let { Format.amountWithUnit(it, ui.unit) } ?: "—")
                    InfoRow(
                        stringResource(R.string.send_network_fee),
                        when {
                            ui.estimating && est == null -> "…"
                            est != null -> Format.amountWithUnit(est.feeSat, ui.unit)
                            else -> "—"
                        },
                    )
                    InfoRow(
                        stringResource(R.string.send_total),
                        est?.let { Format.amountWithUnit(it.totalSat, ui.unit) } ?: "—",
                        emphasize = true,
                    )
                }
                Text(
                    stringResource(R.string.send_onchain_balance, Format.amountWithUnit(wallet.wallet?.onchain?.confirmedSat ?: 0, ui.unit)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
                // Addresses look the same on both chains.
                Text(
                    stringResource(R.string.send_onchain_chain_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            } else {
                AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    InfoRow(stringResource(R.string.send_routing_fee), stringResource(R.string.send_routing_fee_usual))
                    t.expiresAt?.let { ExpiryRow(it) }
                }
                Text(
                    stringResource(R.string.send_lightning_balance, Format.amountWithUnit(wallet.wallet?.lightning?.outboundSat ?: 0, ui.unit)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (ui.onchain) {
                Notice(ui.estimateError.textOrNull())
                if (ui.feesError != null) {
                    Notice(ui.feesError.textOrNull(), kind = NoticeKind.Warning)
                    com.paulscode.lightningfork.ui.components.QuietButton(stringResource(R.string.send_try_again), onClick = vm::reloadFees)
                }
            }
            ui.fees?.warning?.let { Notice(it, kind = NoticeKind.Info, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
        }
        // Re-read once a second, so the button notices a request expiring.
        val tick by androidx.compose.runtime.produceState(0L, t.expiresAt) {
            while (t.expiresAt != null) {
                delay(1000)
                value++
            }
        }
        val blocker = tick.let { vm.blocker(ui) }?.text()
        val amount = ui.amountSat
        PrimaryButton(
            when {
                blocker != null -> blocker
                amount != null && t.isBitcoinInvoice -> stringResource(R.string.send_pay_at_most, Format.amountWithUnit(amount, ui.unit, Coin.Btcb2))
                amount != null -> stringResource(R.string.send_send_amount, Format.amountWithUnit(amount, ui.unit))
                else -> stringResource(R.string.send_send)
            },
            onClick = vm::send,
            enabled = blocker == null,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
    }
}

@Composable
private fun RecipientCard(t: PaymentTarget) {
    var explain by remember { mutableStateOf(false) }
    if (explain) Sha256Explainer(onDismiss = { explain = false })
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.kind == "onchain") BitcoinMark(40.dp) else LightningMark(40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(kindTitle(t)), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                val who = t.issuer?.takeIf { it.isNotBlank() }
                if (who != null) Text(who, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            if (t.isBitcoinInvoice) {
                Spacer(Modifier.width(10.dp))
                val sha256Spoken = stringResource(R.string.send_sha256_pill_spoken)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClickLabel = stringResource(R.string.send_sha256_explain_label)) { explain = true }
                        // Read as words, not the ⓘ glyph.
                        .clearAndSetSemantics {
                            contentDescription = sha256Spoken
                            role = Role.Button
                            onClick(label = sha256Spoken) {
                                explain = true
                                true
                            }
                        },
                ) {
                    Pill(stringResource(R.string.send_sha256_pill), color = Warning)
                }
            }
        }
        if (t.description.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.send_description_quoted, t.description), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        }
        Spacer(Modifier.height(14.dp))
        val shown = if (t.kind == "onchain") Format.grouped(t.address ?: t.request) else Format.middle(t.request, 18, 12)
        Text(
            shown,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = TextMuted,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceRaised.copy(alpha = 0.6f))
                .padding(12.dp),
        )
    }
}

/**
 * What a SHA256 invoice costs here: the most, which the payment is held to,
 * and what to expect; who pays it and what that means; its fees; and,
 * folded away, how the price was made. Every amount is named for its chain.
 */
@Composable
private fun BitcoinInvoiceReview(ui: SendUi, t: PaymentTarget, wallet: WalletState) {
    val est = t.estimate
    var details by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    if (explain) Sha256Explainer(onDismiss = { explain = false })
    AppCard(Modifier.fillMaxWidth()) {
        if (est != null) {
            Text(stringResource(R.string.send_at_most), style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            val most = est.maxIncomingSat + est.routingFeeLimitSat
            AnimatedAmount(most, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary, coin = Coin.Btcb2)
            Format.fiat(most, wallet.fiatPrice, wallet.fiatCurrency)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.send_expected, Format.amountWithUnit(est.incomingSat, ui.unit, Coin.Btcb2)),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            val who = est.serviceLabel.takeIf { it.isNotBlank() }
            Text(
                if (who != null) stringResource(R.string.send_paid_through, who) else stringResource(R.string.send_paid_through_your_service),
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )
            Text(
                stringResource(R.string.send_how_this_works),
                style = MaterialTheme.typography.bodySmall,
                color = Accent,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(role = Role.Button) { explain = true }
                    .padding(vertical = 4.dp),
            )
        } else {
            Text(stringResource(R.string.send_amount_sha256), style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            val amount = t.amountSat
            if (amount != null) {
                AnimatedAmount(amount, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary, coin = Coin.Sha256)
            } else {
                Text(stringResource(R.string.send_none_given), style = MaterialTheme.typography.titleMedium, color = TextFaint)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        t.amountSat?.let { sha ->
            val fiat = est?.let { Format.sha256Fiat(sha, it.rate, wallet.fiatPrice, wallet.fiatCurrency) }
            val amount = Format.amountWithUnit(sha, ui.unit, Coin.Sha256)
            InfoRow(
                stringResource(R.string.send_pays_sha256),
                if (fiat != null) stringResource(R.string.send_amount_with_fiat, amount, fiat) else amount,
            )
        }
        est?.let { InfoRow(stringResource(R.string.send_service_fee), stringResource(R.string.send_service_fee_included, Format.amountWithUnit(it.feeSat, ui.unit, Coin.Btcb2))) }
        t.expiresAt?.let { ExpiryRow(it) }
        val expanded = stringResource(R.string.send_expanded)
        val collapsed = stringResource(R.string.send_collapsed)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { details = !details }
                .semantics { stateDescription = if (details) expanded else collapsed }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.send_details), style = MaterialTheme.typography.bodyMedium, color = Accent, modifier = Modifier.weight(1f))
            Icon(
                if (details) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(details) {
            Column {
                if (est != null && est.rate > 0) {
                    InfoRow(stringResource(R.string.send_rate), stringResource(R.string.send_rate_inverse, Format.inverseRate(est.rate)))
                    InfoRow("", stringResource(R.string.send_rate_direct, Format.rate(est.rate)), valueColor = TextMuted)
                }
                val ref = t.reference
                if (ref != null) {
                    InfoRow(
                        stringResource(R.string.send_against_market),
                        if (ref.premium >= 0) stringResource(R.string.send_premium_above, Format.percent(ref.premium)) else stringResource(R.string.send_premium_below, Format.percent(-ref.premium)),
                        valueColor = if (ref.withinLimit) TextPrimary else Warning,
                    )
                    InfoRow(stringResource(R.string.send_allowed), stringResource(R.string.send_allowed_up_to, Format.percent(ref.premiumAllowed)))
                    if (ref.source.isNotBlank()) InfoRow(stringResource(R.string.send_market_rate_from), ref.source)
                } else if (t.referenceError != null) {
                    InfoRow(stringResource(R.string.send_market_rate), stringResource(R.string.send_not_available), valueColor = Warning)
                }
                if (est != null && est.minSat != null && est.maxSat != null && est.maxSat > 0) {
                    InfoRow(stringResource(R.string.send_service_pays), stringResource(R.string.send_service_pays_range, Format.sats(est.minSat), Format.amountWithUnit(est.maxSat, AmountUnitSats, Coin.Sha256)))
                }
                if (t.description.isNotBlank()) InfoRow(stringResource(R.string.send_description), t.description)
            }
        }
    }
    if (est != null) {
        Text(
            stringResource(R.string.send_includes_routing, Format.amountWithUnit(est.routingFeeLimitSat, ui.unit, Coin.Btcb2)),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
    }
    Text(
        stringResource(R.string.send_lightning_balance, Format.amountWithUnit(wallet.wallet?.lightning?.outboundSat ?: 0, ui.unit, Coin.Btcb2)),
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )
}

private val AmountUnitSats = com.paulscode.lightningfork.data.AmountUnit.Sats

/** What paying a SHA256 invoice means: who pays, what you risk, what it costs, how long. */
@Composable
fun Sha256Explainer(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.send_explainer_ok)) } },
        title = { Text(stringResource(R.string.send_explainer_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.send_explainer_1))
                Text(stringResource(R.string.send_explainer_2))
                Text(stringResource(R.string.send_explainer_3))
                Text(stringResource(R.string.send_explainer_4))
                Text(stringResource(R.string.send_explainer_5), color = TextMuted)
            }
        },
    )
}

@Composable
private fun ExpiryRow(expiresAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(expiresAt) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    val left = expiresAt - now
    InfoRow(
        stringResource(R.string.send_expires),
        if (left <= 0) stringResource(R.string.send_expired) else stringResource(R.string.send_expires_in, Format.countdown(left)),
        valueColor = if (left < 120) Warning else TextPrimary,
    )
}

@Composable
private fun FeeSelector(ui: SendUi, vm: SendViewModel) {
    val fees = ui.fees
    Text(stringResource(R.string.send_network_fee), style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(FeeLevel.Low, FeeLevel.Medium, FeeLevel.High).forEach { level ->
            val rate = fees?.let {
                when (level) {
                    FeeLevel.Low -> it.low
                    FeeLevel.Medium -> it.medium
                    FeeLevel.High -> it.high
                }
            }
            val selected = ui.feeLevel == level
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (selected) SurfaceRaised else Surface)
                    .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Accent else Border), shape)
                    .clickable { vm.onFeeLevel(level) }
                    .padding(vertical = 12.dp, horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The levels' names and times are the app's own, in the
                // phone's language; the node's rates are what it gives.
                Text(
                    stringResource(
                        when (level) {
                            FeeLevel.Low -> R.string.send_fee_low
                            FeeLevel.Medium -> R.string.send_fee_medium
                            FeeLevel.High -> R.string.send_fee_high
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge, color = if (selected) TextPrimary else TextMuted)
                Spacer(Modifier.height(4.dp))
                Text(
                    rate?.let { stringResource(R.string.send_sat_per_vb, Format.sats(it.satPerVbyte)) } ?: "…",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) Accent else TextPrimary,
                )
                Text(
                    stringResource(
                        when (level) {
                            FeeLevel.Low -> R.string.send_fee_eta_low
                            FeeLevel.Medium -> R.string.send_fee_eta_medium
                            FeeLevel.High -> R.string.send_fee_eta_high
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    fees?.source?.let {
        Text(
            when (it.kind) {
                "node" -> stringResource(R.string.send_rates_from_node)
                "minimum" -> stringResource(R.string.send_rates_from_minimum)
                else -> stringResource(R.string.send_rates_from, it.name)
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextFaint,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

@Composable
private fun CenteredStatus(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun SendingStep(ui: SendUi, onClose: () -> Unit) {
    // Leaving is safe once it is under way: the payment is kept on the
    // phone before it goes, the node carries on, and Home offers to check.
    var canLeave by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(10_000)
        canLeave = true
    }
    BackHandler(enabled = canLeave && !ui.repricing) { onClose() }
    Column(Modifier.fillMaxSize()) {
        CenteredStatus(Modifier.weight(1f)) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(84.dp))
                if (ui.onchain) BitcoinMark(48.dp) else LightningMark(48.dp)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                when {
                    ui.repricing -> stringResource(R.string.send_getting_price)
                    ui.checking -> stringResource(R.string.send_checking)
                    else -> stringResource(R.string.send_sending)
                },
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    ui.repricing -> stringResource(R.string.send_sending_repricing)
                    ui.checking -> stringResource(R.string.send_sending_checking)
                    ui.sendingBitcoinInvoice -> stringResource(R.string.send_sending_sha256)
                    ui.onchain -> stringResource(R.string.send_sending_onchain)
                    else -> stringResource(R.string.send_sending_lightning)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
        AnimatedVisibility(canLeave && !ui.repricing) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SecondaryButton(stringResource(R.string.send_close_carries_on), onClick = onClose, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.send_close_carries_on_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DoneStep(ui: SendUi, onClose: () -> Unit) {
    val r = ui.result ?: return
    val context = LocalContext.current
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
    Column(Modifier.fillMaxSize()) {
        CenteredStatus(Modifier.weight(1f)) {
            Box(
                Modifier
                    .size(96.dp)
                    .scale(pop.value)
                    .shadow(28.dp, CircleShape, ambientColor = Success, spotColor = Success)
                    .clip(CircleShape)
                    .background(Success),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(28.dp))
            val bitcoinAmount = r.bitcoinAmountSat
            Text(
                when {
                    bitcoinAmount != null -> stringResource(R.string.send_sha256_paid)
                    r.lightning -> stringResource(R.string.send_sent)
                    else -> stringResource(R.string.send_sent_confirming)
                },
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            val coin = if (bitcoinAmount != null) Coin.Btcb2 else null
            AnimatedAmount(
                bitcoinAmount ?: r.amountSat,
                ui.unit,
                style = MaterialTheme.typography.displaySmall,
                color = TextPrimary,
                coin = if (bitcoinAmount != null) Coin.Sha256 else null,
            )
            if (bitcoinAmount != null) {
                Text(stringResource(R.string.send_paid_on_sha256), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Spacer(Modifier.height(24.dp))
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                if (bitcoinAmount != null) InfoRow(stringResource(R.string.send_cost), Format.amountWithUnit(r.amountSat, ui.unit, coin))
                InfoRow(
                    stringResource(if (r.lightning) R.string.send_routing_fee else R.string.send_network_fee),
                    // The node reports a Lightning fee as paid; an on-chain one
                    // here is the estimate it was sent at.
                    Format.amountWithUnit(r.feeSat, ui.unit, coin).let { fee ->
                        if (r.lightning) fee else stringResource(R.string.format_approx, fee)
                    },
                )
                // What it came to here, against the most agreed to.
                if (bitcoinAmount != null) {
                    InfoRow(stringResource(R.string.send_total), Format.amountWithUnit(r.amountSat + r.feeSat, ui.unit, coin), emphasize = true)
                }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        Clipboard.copySensitive(context, r.reference, context.getString(if (r.lightning) R.string.activityscreen_clip_preimage else R.string.send_clip_txid))
                    }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (r.lightning) stringResource(R.string.send_proof_of_payment) else stringResource(R.string.send_transaction), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    Spacer(Modifier.weight(1f))
                    Text(Format.middle(r.reference, 8, 8), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.send_copy), tint = Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
        PrimaryButton(stringResource(R.string.send_done), onClick = onClose, modifier = Modifier.fillMaxWidth().padding(20.dp))
    }
}

@Composable
private fun FailedStep(ui: SendUi, vm: SendViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    // A wait the node asked for before trying again, counted down.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.retryAtMs) {
        while (ui.retryAtMs != null && System.currentTimeMillis() < ui.retryAtMs) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
        nowMs = System.currentTimeMillis()
    }
    val waitSeconds = ui.retryAtMs?.let { ((it - nowMs) / 1000).coerceAtLeast(0) } ?: 0
    Column(Modifier.fillMaxSize()) {
        CenteredStatus(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Refused as paid already, or paid by the service without
            // collecting: not failures.
            val calm = ui.onItsWay || ui.alreadyPaid || ui.needsOperator
            val tint = when {
                calm -> Accent
                ui.uncertain -> Warning
                else -> Danger
            }
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        ui.onItsWay -> Icons.Rounded.Schedule
                        ui.alreadyPaid || ui.needsOperator -> Icons.Rounded.Info
                        else -> Icons.Rounded.ErrorOutline
                    },
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                when {
                    ui.onItsWay -> stringResource(R.string.send_failed_on_its_way)
                    ui.uncertain -> stringResource(R.string.send_failed_uncertain)
                    ui.alreadyPaid -> stringResource(R.string.send_failed_already_paid)
                    ui.needsOperator -> stringResource(R.string.send_sha256_paid)
                    else -> stringResource(R.string.send_failed)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(ui.error.textOrNull().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
            ui.hint?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.text(), style = MaterialTheme.typography.bodySmall, color = TextPrimary, textAlign = TextAlign.Center)
            }
            if (ui.uncertain) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (ui.onItsWay) {
                        ui.maxHoldHours?.let { stringResource(R.string.send_on_its_way_within, Format.hoursRoughly(it)) }
                            ?: stringResource(R.string.send_on_its_way)
                    } else {
                        stringResource(R.string.send_uncertain_explained)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                    textAlign = TextAlign.Center,
                )
                if (ui.onItsWay && ui.autoChecking) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.send_checking_on_its_own), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
            ui.proof?.let { proof ->
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { Clipboard.copySensitive(context, proof, context.getString(R.string.activityscreen_clip_preimage)) }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.send_proof_of_payment), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    Spacer(Modifier.weight(1f))
                    Text(Format.middle(proof, 8, 8), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.send_copy), tint = Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                // Too old to ask about without sending it: forget it, or leave
                // it for later.
                ui.tooOldToCheck -> {
                    PrimaryButton(
                        stringResource(R.string.send_forget_payment),
                        onClick = {
                            vm.dismissUncertain()
                            onClose()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryButton(stringResource(R.string.send_close), onClick = onClose, modifier = Modifier.fillMaxWidth())
                }
                ui.uncertain -> {
                    PrimaryButton(stringResource(R.string.send_check_again), onClick = vm::retry, modifier = Modifier.fillMaxWidth())
                    SecondaryButton(stringResource(R.string.send_close), onClick = onClose, modifier = Modifier.fillMaxWidth())
                    com.paulscode.lightningfork.ui.components.QuietButton(
                        stringResource(R.string.send_forget),
                        onClick = {
                            vm.dismissUncertain()
                            onClose()
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = TextMuted,
                    )
                }
                // Nothing to try again: paid already, something to change in
                // the dashboard first, or nothing to try it with.
                !vm.canRetry(ui) -> PrimaryButton(stringResource(R.string.send_close), onClick = onClose, modifier = Modifier.fillMaxWidth())
                else -> {
                    PrimaryButton(
                        if (waitSeconds > 0) {
                            pluralStringResource(R.plurals.send_try_again_in_seconds, waitSeconds.toInt(), Format.sats(waitSeconds))
                        } else {
                            stringResource(R.string.send_try_again)
                        },
                        onClick = vm::retry,
                        enabled = waitSeconds == 0L,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ui.target != null) {
                        SecondaryButton(stringResource(R.string.send_back), onClick = vm::backToReview, modifier = Modifier.fillMaxWidth())
                    } else {
                        SecondaryButton(stringResource(R.string.send_close), onClick = onClose, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
