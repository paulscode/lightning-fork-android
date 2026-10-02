package com.paulscode.lightningfork.ui.receive

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paulscode.lightningfork.ui.components.AmountField
import com.paulscode.lightningfork.ui.components.AnimatedAmount
import com.paulscode.lightningfork.ui.components.AppTextField
import com.paulscode.lightningfork.ui.components.Notice
import com.paulscode.lightningfork.ui.components.NoticeKind
import com.paulscode.lightningfork.ui.components.PrimaryButton
import com.paulscode.lightningfork.ui.components.QrCode
import com.paulscode.lightningfork.ui.components.QuietButton
import com.paulscode.lightningfork.ui.components.SecondaryButton
import com.paulscode.lightningfork.ui.components.SegmentedToggle
import com.paulscode.lightningfork.ui.components.TopBar
import com.paulscode.lightningfork.ui.theme.Accent
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.Success
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
fun ReceiveScreen(vm: ReceiveViewModel, wallet: WalletState, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("Receive", onBack = onClose)
        if (!(ui.tab == ReceiveTab.Lightning && ui.invoiceState == "settled")) {
            SegmentedToggle(
                options = listOf("Lightning", "Bitcoin"),
                selected = if (ui.tab == ReceiveTab.Lightning) 0 else 1,
                onSelect = { vm.selectTab(if (it == 0) ReceiveTab.Lightning else ReceiveTab.Onchain) },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        AnimatedContent(
            targetState = ui.tab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "receiveTab",
            modifier = Modifier.weight(1f),
        ) { tab ->
            when (tab) {
                ReceiveTab.Lightning -> LightningTab(ui, vm, wallet, onClose)
                ReceiveTab.Onchain -> OnchainTab(ui, vm, wallet)
            }
        }
    }
}

@Composable
private fun LightningTab(ui: ReceiveUi, vm: ReceiveViewModel, wallet: WalletState, onClose: () -> Unit) {
    val inv = ui.invoice
    when {
        inv == null -> InvoiceForm(ui, vm, wallet)
        ui.invoiceState == "settled" -> Received(ui.paidSat, ui, onClose, vm::newInvoice)
        else -> InvoiceView(ui, vm)
    }
}

@Composable
private fun InvoiceForm(ui: ReceiveUi, vm: ReceiveViewModel, wallet: WalletState) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Request a payment", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Leave the amount empty to let the payer choose it.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            Spacer(Modifier.height(22.dp))
            AmountField(
                text = ui.amountText,
                onTextChange = vm::onAmountText,
                unit = ui.unit,
                onToggleUnit = vm::toggleUnit,
                placeholder = "Any amount",
                fiat = Format.parseAmount(ui.amountText, ui.unit)?.let { Format.fiat(it, wallet.usdPrice) },
            )
            Spacer(Modifier.height(16.dp))
            AppTextField(
                value = ui.memo,
                onValueChange = vm::onMemo,
                label = "Description (optional)",
                placeholder = "What's it for?",
            )
            val inbound = wallet.wallet?.lightning?.inboundSat
            if (inbound != null) {
                val asked = Format.parseAmount(ui.amountText, ui.unit) ?: 0
                Text(
                    "You can receive up to ${Format.amountWithUnit(inbound, ui.unit)} over Lightning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (asked > inbound) Warning else TextMuted,
                    modifier = Modifier.padding(top = 14.dp, start = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Notice(ui.error)
        }
        PrimaryButton(
            "Create invoice",
            onClick = vm::createInvoice,
            loading = ui.creating,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
    }
}

@Composable
private fun InvoiceView(ui: ReceiveUi, vm: ReceiveViewModel) {
    val inv = ui.invoice ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(inv.paymentHash) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    val expired = ui.invoiceState == "expired" || inv.expiresAt in 1..now
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.widthIn(max = 340.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCode("lightning:" + inv.paymentRequest.uppercase(), Modifier.fillMaxWidth())
                if (expired) {
                    Box(
                        Modifier.matchParentSize().clip(RoundedCornerShape(20.dp)).background(Page.copy(alpha = 0.86f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Expired", style = MaterialTheme.typography.headlineSmall, color = Warning)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (inv.amountSat != null) {
                AnimatedAmount(inv.amountSat, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary)
            } else {
                Text("Any amount", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            }
            if (inv.memo.isNotBlank()) {
                Text("“${inv.memo}”", style = MaterialTheme.typography.bodyLarge, color = TextMuted, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            if (!expired) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Text(
                        "  Waiting for payment · expires in ${Format.countdown(inv.expiresAt - now)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            CopyShareRow(text = inv.paymentRequest, label = "invoice", shareText = "lightning:" + inv.paymentRequest)
            Spacer(Modifier.height(12.dp))
            Text(
                Format.middle(inv.paymentRequest, 20, 12),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = TextFaint,
            )
        }
        SecondaryButton(
            if (expired) "Create a new invoice" else "New invoice",
            onClick = vm::newInvoice,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
    }
}

@Composable
private fun Received(sats: Long, ui: ReceiveUi, onClose: () -> Unit, onAnother: () -> Unit) {
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
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
            Text("Received", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            AnimatedAmount(sats, ui.unit, style = MaterialTheme.typography.displaySmall, color = TextPrimary)
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Done", onClick = onClose, modifier = Modifier.fillMaxWidth())
            SecondaryButton("Request another", onClick = onAnother, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun OnchainTab(ui: ReceiveUi, vm: ReceiveViewModel, wallet: WalletState) {
    var withAmount by remember { mutableStateOf(ui.onchainAmountText.isNotBlank()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val address = ui.address
        val uri = ui.onchainUri
        Box(Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (uri != null) {
                QrCode(uri, Modifier.fillMaxWidth(), bitcoin = true)
            } else {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(SurfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ui.loadingAddress) CircularProgressIndicator(color = Accent)
                }
            }
        }
        AnimatedVisibility(ui.incomingSat > 0, enter = fadeIn(), exit = fadeOut()) {
            Notice(
                "Payment of ${Format.amountWithUnit(ui.incomingSat, ui.unit)} arriving. It is spendable once it confirms.",
                kind = NoticeKind.Info,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        if (address != null) {
            Text(
                Format.grouped(address.address),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(18.dp))
            CopyShareRow(text = address.address, label = "address", shareText = uri ?: address.address)
        }
        Spacer(Modifier.height(16.dp))
        if (withAmount) {
            AmountField(
                text = ui.onchainAmountText,
                onTextChange = vm::onOnchainAmountText,
                unit = ui.unit,
                onToggleUnit = vm::toggleUnit,
                label = "Amount to ask for (optional)",
                fiat = Format.parseAmount(ui.onchainAmountText, ui.unit)?.let { Format.fiat(it, wallet.usdPrice) },
            )
        }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            if (!withAmount) QuietButton("Add an amount", onClick = { withAmount = true })
            QuietButton("New address", onClick = { vm.loadAddress(fresh = true) }, enabled = !ui.loadingAddress)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Bitcoin sent here takes a block or more to confirm. For instant payments, use Lightning.",
            style = MaterialTheme.typography.bodySmall,
            color = TextFaint,
            textAlign = TextAlign.Center,
        )
        Notice(ui.error, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun CopyShareRow(text: String, label: String, shareText: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(
            if (copied) "Copied" else "Copy",
            onClick = {
                Clipboard.copyPlain(context, text, label)
                copied = true
            },
            icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            modifier = Modifier.weight(1f),
            contentColor = if (copied) Success else TextPrimary,
        )
        SecondaryButton(
            "Share",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(send, null))
            },
            icon = Icons.Rounded.Share,
            modifier = Modifier.weight(1f),
        )
    }
}
