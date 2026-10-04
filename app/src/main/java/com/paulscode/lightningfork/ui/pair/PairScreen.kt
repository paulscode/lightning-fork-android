package com.paulscode.lightningfork.ui.pair

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.pairing.PairPhase
import com.paulscode.lightningfork.ui.components.AppCard
import com.paulscode.lightningfork.ui.components.AppTextField
import com.paulscode.lightningfork.ui.components.InfoRow
import com.paulscode.lightningfork.ui.components.Notice
import com.paulscode.lightningfork.ui.components.PrimaryButton
import com.paulscode.lightningfork.ui.components.QuietButton
import com.paulscode.lightningfork.ui.components.SecondaryButton
import com.paulscode.lightningfork.ui.scan.ScanScreen
import com.paulscode.lightningfork.ui.theme.Accent
import com.paulscode.lightningfork.ui.theme.AccentGlow
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.SurfaceRaised
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.ui.theme.TextPrimary
import com.paulscode.lightningfork.util.Format

@Composable
fun PairScreen(vm: PairViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun paste() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        vm.onPasted(cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
    }
    if (ui.scanning) {
        BackHandler { vm.stopScan() }
        ScanScreen(
            title = "Scan the pairing code",
            hint = "In the dashboard: menu → Mobile app",
            onResult = vm::onScanned,
            onPaste = ::paste,
            onClose = vm::stopScan,
        )
        return
    }
    if (ui.payload != null) BackHandler(enabled = ui.phase == null) { vm.reset() }

    Box(Modifier.fillMaxSize().background(Page)) {
        Image(
            painterResource(R.drawable.storm),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(520.dp).alpha(0.55f),
        )
        Box(
            Modifier.fillMaxWidth().height(520.dp).background(
                Brush.verticalGradient(0f to Page.copy(alpha = 0.2f), 0.6f to Page.copy(alpha = 0.7f), 1f to Page)
            )
        )
        AnimatedContent(
            targetState = when {
                ui.phase != null -> 2
                ui.payload != null -> 1
                else -> 0
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "pair",
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        ) { step ->
            when (step) {
                0 -> Welcome(ui, onScan = vm::startScan, onPaste = ::paste)
                1 -> Confirm(ui, vm)
                else -> Progress(ui, vm)
            }
        }
    }
}

@Composable
private fun Logo() {
    Box(
        Modifier
            .size(84.dp)
            .shadow(30.dp, CircleShape, ambientColor = AccentGlow, spotColor = Accent)
            .clip(CircleShape),
    ) {
        Image(painterResource(R.drawable.storm), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Welcome(ui: PairUi, onScan: () -> Unit, onPaste: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(48.dp))
        Logo()
        Spacer(Modifier.height(24.dp))
        Text("Lightning Fork", style = MaterialTheme.typography.displaySmall, color = TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Send and receive BTCB2 from your own node, on the BLAKE2b chain.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted,
        )
        Spacer(Modifier.height(28.dp))
        Step(1, "Open your Lightning Fork dashboard on your StartOS or Umbrel server.")
        Step(2, "In its menu, choose Mobile app, then Pair a phone.")
        Step(3, "Scan the code it shows.")
        Spacer(Modifier.height(20.dp))
        Notice(ui.error)
        Spacer(Modifier.height(32.dp))
        PrimaryButton("Scan pairing code", onClick = onScan, icon = Icons.Rounded.QrCodeScanner, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Paste code", onClick = onPaste, icon = Icons.Rounded.ContentPaste, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun Confirm(ui: PairUi, vm: PairViewModel) {
    val p = ui.payload ?: return
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(32.dp))
            Logo()
            Spacer(Modifier.height(20.dp))
            Text("Pair with your node", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "This phone gets a key of its own, which you can remove from the dashboard at any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            if (ui.fromLink) {
                Spacer(Modifier.height(14.dp))
                Notice(
                    "This code came from a link. Pair only if you opened it from your own dashboard just now, and the addresses below are your node's.",
                    kind = com.paulscode.lightningfork.ui.components.NoticeKind.Warning,
                )
            }
            Spacer(Modifier.height(20.dp))
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                p.lan?.let { InfoRow("Local address", it.removePrefix("https://")) }
                p.ip?.let { InfoRow("Local IP", it.removePrefix("https://")) }
                p.onion?.let { InfoRow("Tor", Format.middle(it.substringAfter("://"), 10, 12)) }
                p.ca?.let { InfoRow("Certificate", it.take(11) + "…") }
            }
            Spacer(Modifier.height(18.dp))
            AppTextField(
                value = ui.label,
                onValueChange = vm::onLabel,
                label = "Name this phone",
                placeholder = "My phone",
            )
            Spacer(Modifier.height(14.dp))
            Notice(ui.error)
        }
        PrimaryButton("Pair", onClick = vm::pair, modifier = Modifier.fillMaxWidth(), enabled = ui.recoverable || ui.error == null)
        Spacer(Modifier.height(4.dp))
        QuietButton("Scan a different code", onClick = { vm.reset(); vm.startScan() }, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun Progress(ui: PairUi, vm: PairViewModel) {
    val torProgress by vm.tor.progress.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(100.dp))
            Logo()
        }
        Spacer(Modifier.height(28.dp))
        Text(
            when (ui.phase) {
                PairPhase.Reaching -> "Reaching your node…"
                PairPhase.StartingTor -> "Starting Tor… $torProgress%"
                PairPhase.Claiming -> "Pairing…"
                PairPhase.Finishing, PairPhase.Done -> "Paired"
                null -> ""
            },
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (ui.phase == PairPhase.StartingTor) "Away from home, the phone reaches your node over Tor. The first start takes up to a minute." else "",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

