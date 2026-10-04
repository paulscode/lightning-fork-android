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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.paulscode.lightningfork.ui.theme.Warning
import com.paulscode.lightningfork.util.Clipboard
import com.paulscode.lightningfork.util.Coin
import com.paulscode.lightningfork.util.Format
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
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
            title = "Scan to pay",
            hint = "Point at a payment QR code",
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
        TopBar("Send", onBack = onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Who are you paying?", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "A BTCB2 address, a Lightning invoice or offer, or a SHA256 invoice.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigChoice("Scan", "Use the camera", Icons.Rounded.QrCodeScanner, onScan, Modifier.weight(1f))
                BigChoice("Paste", "From the clipboard", Icons.Rounded.ContentPaste, onPaste, Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
            AppTextField(
                value = ui.input,
                onValueChange = vm::onInput,
                label = "Or type it",
                placeholder = "bc1… · lnbc… · lno1…",
                singleLine = false,
                minLines = 3,
                imeAction = ImeAction.Done,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(14.dp))
            Notice(ui.inputError)
        }
        PrimaryButton(
            "Continue",
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

private fun kindTitle(t: PaymentTarget): String = when (t.kind) {
    "onchain" -> "On-chain address"
    "bolt11" -> "Lightning invoice"
    "offer" -> "Lightning offer"
    "bolt12-invoice" -> "Lightning invoice (offer)"
    "bitcoin-invoice" -> "Lightning invoice"
    else -> "Payment"
}

@Composable
private fun ReviewStep(ui: SendUi, vm: SendViewModel, wallet: WalletState) {
    val t = ui.active ?: return
    val target = ui.target!!
    Column(Modifier.fillMaxSize()) {
        TopBar("Review", onBack = vm::backToInput)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            if (target.fallback != null && target.isLightning) {
                SegmentedToggle(
                    options = listOf("Lightning", "On-chain"),
                    selected = if (ui.payOnchain) 1 else 0,
                    onSelect = { vm.choosePayOnchain(it == 1) },
                )
                Spacer(Modifier.height(16.dp))
            }
            Notice(ui.reviewNotice, kind = NoticeKind.Warning, modifier = Modifier.padding(bottom = 16.dp))
            // Why a SHA256 invoice can't be paid now, first, where it is seen.
            if (t.isBitcoinInvoice && (!t.payable || t.estimate == null)) {
                Notice(
                    t.message ?: "This SHA256 invoice can't be paid right now.",
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
                    Text("Amount", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Spacer(Modifier.height(6.dp))
                    val amount = ui.amountSat
                    if (amount != null) {
                        AnimatedAmount(amount, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary)
                        Format.fiat(amount, wallet.fiatPrice, wallet.fiatCurrency)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    } else {
                        Text("Working it out…", style = MaterialTheme.typography.titleMedium, color = TextFaint)
                    }
                }
            }
            if (ui.onchain && t.amountEditable) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Send everything", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "All confirmed on-chain funds, less the fee",
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
                    label = "Note to the recipient (optional)",
                    placeholder = "Thanks!",
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
                    InfoRow("Amount", ui.amountSat?.let { Format.amountWithUnit(it, ui.unit) } ?: "—")
                    InfoRow(
                        "Network fee",
                        when {
                            ui.estimating && est == null -> "…"
                            est != null -> Format.amountWithUnit(est.feeSat, ui.unit)
                            else -> "—"
                        },
                    )
                    InfoRow(
                        "Total",
                        est?.let { Format.amountWithUnit(it.totalSat, ui.unit) } ?: "—",
                        emphasize = true,
                    )
                }
                Text(
                    "On-chain balance: ${Format.amountWithUnit(wallet.wallet?.onchain?.confirmedSat ?: 0, ui.unit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
                // Addresses look the same on both chains.
                Text(
                    "Sends BTCB2 on the BLAKE2b chain. Not for someone expecting BTC (SHA256).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            } else {
                AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    InfoRow("Routing fee", "Usually a few sats")
                    t.expiresAt?.let { ExpiryRow(it) }
                }
                Text(
                    "Lightning balance: ${Format.amountWithUnit(wallet.wallet?.lightning?.outboundSat ?: 0, ui.unit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (ui.onchain) {
                Notice(ui.estimateError)
                if (ui.feesError != null) {
                    Notice(ui.feesError, kind = NoticeKind.Warning)
                    com.paulscode.lightningfork.ui.components.QuietButton("Try again", onClick = vm::reloadFees)
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
        val blocker = tick.let { vm.blocker(ui) }
        val amount = ui.amountSat
        PrimaryButton(
            when {
                blocker != null -> blocker
                amount != null && t.isBitcoinInvoice -> "Pay at most ${Format.amountWithUnit(amount, ui.unit, Coin.Btcb2)}"
                amount != null -> "Send ${Format.amountWithUnit(amount, ui.unit)}"
                else -> "Send"
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
                Text(kindTitle(t), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                val who = t.issuer?.takeIf { it.isNotBlank() }
                if (who != null) Text(who, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            if (t.isBitcoinInvoice) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClickLabel = "What paying a SHA256 invoice means") { explain = true },
                ) {
                    Pill("SHA256 invoice ⓘ", color = Warning)
                }
            }
        }
        if (t.description.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text("“${t.description}”", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
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
            Text("At most", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            val most = est.maxIncomingSat + est.routingFeeLimitSat
            AnimatedAmount(most, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary, coin = Coin.Btcb2)
            Format.fiat(most, wallet.fiatPrice, wallet.fiatCurrency)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Expected ${Format.amountWithUnit(est.incomingSat, ui.unit, Coin.Btcb2)} plus routing, if the price holds until it is paid",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            val who = est.serviceLabel.ifBlank { "your service" }
            Text(
                "Paid through $who. It is paid only if the SHA256 invoice is paid; otherwise your payment comes back.",
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )
            Text(
                "How this works",
                style = MaterialTheme.typography.bodySmall,
                color = Accent,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(role = Role.Button) { explain = true }
                    .padding(vertical = 4.dp),
            )
        } else {
            Text("Amount on the SHA256 chain", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            val amount = t.amountSat
            if (amount != null) {
                AnimatedAmount(amount, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary, coin = Coin.Sha256)
            } else {
                Text("None given", style = MaterialTheme.typography.titleMedium, color = TextFaint)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        t.amountSat?.let { sha ->
            val fiat = est?.let { Format.sha256Fiat(sha, it.rate, wallet.fiatPrice, wallet.fiatCurrency) }
            InfoRow("Pays on the SHA256 chain", Format.amountWithUnit(sha, ui.unit, Coin.Sha256) + (fiat?.let { ", $it" } ?: ""))
        }
        est?.let { InfoRow("Service fee", "${Format.amountWithUnit(it.feeSat, ui.unit, Coin.Btcb2)}, included") }
        t.expiresAt?.let { ExpiryRow(it) }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { details = !details }
                .semantics { stateDescription = if (details) "Expanded" else "Collapsed" }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Details", style = MaterialTheme.typography.bodyMedium, color = Accent, modifier = Modifier.weight(1f))
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
                    InfoRow("Rate", "1 BTC (SHA256) ≈ ${Format.inverseRate(est.rate)} BTCB2")
                    InfoRow("", "1 BTCB2 = ${Format.rate(est.rate)} BTC (SHA256)", valueColor = TextMuted)
                }
                val ref = t.reference
                if (ref != null) {
                    InfoRow(
                        "Against the market",
                        if (ref.premium >= 0) "${Format.percent(ref.premium)} above" else "${Format.percent(-ref.premium)} below",
                        valueColor = if (ref.withinLimit) TextPrimary else Warning,
                    )
                    InfoRow("Allowed", "Up to ${Format.percent(ref.premiumAllowed)} above")
                    if (ref.source.isNotBlank()) InfoRow("Market rate from", ref.source)
                } else if (t.referenceError != null) {
                    InfoRow("Market rate", "Not available", valueColor = Warning)
                }
                if (est != null && est.minSat != null && est.maxSat != null && est.maxSat > 0) {
                    InfoRow("Service pays", "${Format.sats(est.minSat)} to ${Format.amountWithUnit(est.maxSat, AmountUnitSats, Coin.Sha256)}")
                }
                if (t.description.isNotBlank()) InfoRow("Description", t.description)
            }
        }
    }
    if (est != null) {
        Text(
            "Includes up to ${Format.amountWithUnit(est.routingFeeLimitSat, ui.unit, Coin.Btcb2)} for routing to the service.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
    }
    Text(
        "Lightning balance: ${Format.amountWithUnit(wallet.wallet?.lightning?.outboundSat ?: 0, ui.unit, Coin.Btcb2)}",
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("Paying a SHA256 invoice") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A SHA256 invoice asks to be paid on the SHA256 chain's Lightning network. Your node can't pay there itself, so a service pays it for you, and you pay the service in BTCB2.")
                Text("You don't have to trust the service: your payment to it is locked to the same invoice, so it can only collect by paying the SHA256 invoice. If it doesn't, your payment comes back to you.")
                Text("The price is the service's rate and fee. Your node checks it against the market rate, and you agree to the most it can cost before anything is paid.")
                Text("It is usually paid within a minute or two. If the service stalls, your payment can stay held until its time runs out, at most about two weeks, and then comes back.")
                Text("The service is the one set up in the dashboard, under Paying SHA256 invoices.", color = TextMuted)
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
        "Expires",
        if (left <= 0) "Expired" else "in ${Format.countdown(left)}",
        valueColor = if (left < 120) Warning else TextPrimary,
    )
}

@Composable
private fun FeeSelector(ui: SendUi, vm: SendViewModel) {
    val fees = ui.fees
    Text("Network fee", style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
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
                Text(rate?.label ?: level.name, style = MaterialTheme.typography.labelLarge, color = if (selected) TextPrimary else TextMuted)
                Spacer(Modifier.height(4.dp))
                Text(
                    rate?.let { "${it.satPerVbyte} sat/vB" } ?: "…",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) Accent else TextPrimary,
                )
                Text(
                    rate?.eta?.replace("About ", "~") ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    fees?.source?.let {
        Text(
            "Rates from ${it.name}",
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
                    ui.repricing -> "Getting the price…"
                    ui.checking -> "Checking…"
                    else -> "Sending…"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    ui.repricing -> "Your node is asking the service what this SHA256 invoice costs now."
                    ui.checking -> "Your node is looking up this payment. It is never paid twice."
                    ui.sendingBitcoinInvoice -> "Your node pays the service, which pays the SHA256 invoice. This can take a minute or two."
                    ui.onchain -> "Your node is signing and broadcasting the transaction."
                    else -> "Your node is finding a route to the recipient."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
        AnimatedVisibility(canLeave && !ui.repricing) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SecondaryButton("Close, it carries on", onClick = onClose, modifier = Modifier.fillMaxWidth())
                Text(
                    "Home shows it until it's done, and lets you check it.",
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
                    bitcoinAmount != null -> "SHA256 invoice paid"
                    r.lightning -> "Sent"
                    else -> "Sent · confirming"
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
                Text("paid on the SHA256 chain", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Spacer(Modifier.height(24.dp))
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                if (bitcoinAmount != null) InfoRow("Cost", Format.amountWithUnit(r.amountSat, ui.unit, coin))
                InfoRow(
                    if (r.lightning) "Routing fee" else "Network fee",
                    // The node reports a Lightning fee as paid; an on-chain one
                    // here is the estimate it was sent at.
                    (if (r.lightning) "" else "≈ ") + Format.amountWithUnit(r.feeSat, ui.unit, coin),
                )
                // What it came to here, against the most agreed to.
                if (bitcoinAmount != null) {
                    InfoRow("Total", Format.amountWithUnit(r.amountSat + r.feeSat, ui.unit, coin), emphasize = true)
                }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        Clipboard.copySensitive(context, r.reference, if (r.lightning) "preimage" else "txid")
                    }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (r.lightning) "Proof of payment" else "Transaction", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    Spacer(Modifier.weight(1f))
                    Text(Format.middle(r.reference, 8, 8), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
        PrimaryButton("Done", onClick = onClose, modifier = Modifier.fillMaxWidth().padding(20.dp))
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
                    ui.onItsWay -> "On its way"
                    ui.uncertain -> "Not sure it went through"
                    ui.alreadyPaid -> "Already paid"
                    ui.needsOperator -> "SHA256 invoice paid"
                    else -> "Payment didn't go through"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(ui.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
            ui.hint?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextPrimary, textAlign = TextAlign.Center)
            }
            if (ui.uncertain) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (ui.onItsWay) {
                        "The service pays the SHA256 invoice first, which can take a while. " +
                            (ui.maxHoldHours?.let { "If it never pays, your payment comes back, within ${Format.hoursRoughly(it)}. " } ?: "If it never pays, your payment comes back. ") +
                            "Checking never pays it twice."
                    } else {
                        "Check again asks your node about this same payment and never pays it twice. Your activity shows it too once it went through."
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
                        Text("Checking again on its own", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
            ui.proof?.let { proof ->
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { Clipboard.copySensitive(context, proof, "preimage") }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Proof of payment", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    Spacer(Modifier.weight(1f))
                    Text(Format.middle(proof, 8, 8), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                ui.uncertain -> {
                    PrimaryButton("Check again", onClick = vm::retry, modifier = Modifier.fillMaxWidth())
                    SecondaryButton("Close", onClick = onClose, modifier = Modifier.fillMaxWidth())
                    com.paulscode.lightningfork.ui.components.QuietButton(
                        "I checked my activity: forget this payment",
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
                !vm.canRetry(ui) -> PrimaryButton("Close", onClick = onClose, modifier = Modifier.fillMaxWidth())
                else -> {
                    PrimaryButton(
                        if (waitSeconds > 0) "Try again in ${waitSeconds}s" else "Try again",
                        onClick = vm::retry,
                        enabled = waitSeconds == 0L,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ui.target != null) {
                        SecondaryButton("Back", onClick = vm::backToReview, modifier = Modifier.fillMaxWidth())
                    } else {
                        SecondaryButton("Close", onClick = onClose, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
