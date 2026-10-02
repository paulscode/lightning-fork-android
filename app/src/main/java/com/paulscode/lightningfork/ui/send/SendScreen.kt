package com.paulscode.lightningfork.ui.send

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import com.paulscode.lightningfork.util.Format
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
    var scanning by remember { mutableStateOf(startScanning) }

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
            hint = "Point at a Bitcoin or Lightning QR code",
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
            SendStep.Failed -> vm.backToReview()
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
                SendStep.Sending -> SendingStep(ui)
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
                "A Bitcoin address, a Lightning invoice or an offer.",
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
    "onchain" -> "Bitcoin address"
    "bolt11" -> "Lightning invoice"
    "offer" -> "Lightning offer"
    "bolt12-invoice" -> "Lightning invoice (offer)"
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
            RecipientCard(t)
            Spacer(Modifier.height(18.dp))
            if (t.amountEditable && !(ui.onchain && ui.sendAll)) {
                AmountField(
                    text = ui.amountText,
                    onTextChange = vm::onAmountText,
                    unit = ui.unit,
                    onToggleUnit = vm::toggleUnit,
                    fiat = Format.parseAmount(ui.amountText, ui.unit)?.let { Format.fiat(it, wallet.usdPrice) },
                )
            } else {
                AppCard(Modifier.fillMaxWidth()) {
                    Text("Amount", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Spacer(Modifier.height(6.dp))
                    val amount = ui.amountSat
                    if (amount != null) {
                        AnimatedAmount(amount, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary)
                        Format.fiat(amount, wallet.usdPrice)?.let {
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
            if (ui.onchain) {
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
            Notice(ui.feesError, kind = NoticeKind.Warning)
            ui.fees?.warning?.let { Notice(it, kind = NoticeKind.Info, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
        }
        val blocker = vm.blocker(ui)
        val amount = ui.amountSat
        PrimaryButton(
            when {
                blocker != null -> blocker
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
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.kind == "onchain") BitcoinMark(40.dp) else LightningMark(40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(kindTitle(t), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                val who = t.issuer?.takeIf { it.isNotBlank() }
                if (who != null) Text(who, style = MaterialTheme.typography.bodySmall, color = TextMuted)
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
private fun SendingStep(ui: SendUi) {
    CenteredStatus {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(84.dp))
            if (ui.onchain) BitcoinMark(48.dp) else LightningMark(48.dp)
        }
        Spacer(Modifier.height(28.dp))
        Text("Sending…", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            if (ui.onchain) "Your node is signing and broadcasting the transaction." else "Your node is finding a route to the recipient.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
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
            Text(if (r.lightning) "Sent" else "Sent · confirming", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            AnimatedAmount(r.amountSat, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary)
            Spacer(Modifier.height(24.dp))
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                InfoRow(if (r.lightning) "Routing fee" else "Network fee", Format.amountWithUnit(r.feeSat, ui.unit))
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
    Column(Modifier.fillMaxSize()) {
        CenteredStatus(Modifier.weight(1f)) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background((if (ui.uncertain) Warning else Danger).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = if (ui.uncertain) Warning else Danger, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                if (ui.uncertain) "Not sure it went through" else "Payment didn't go through",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(ui.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
            if (ui.uncertain) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Checking again is safe: your node will not pay twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (ui.uncertain) "Check again" else "Try again", onClick = vm::retry, modifier = Modifier.fillMaxWidth())
            SecondaryButton(if (ui.uncertain) "Close" else "Back", onClick = { if (ui.uncertain) onClose() else vm.backToReview() }, modifier = Modifier.fillMaxWidth())
        }
    }
}
