package com.paulscode.lightningfork.ui.pair

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paulscode.lightningfork.ui.components.PrimaryButton
import com.paulscode.lightningfork.ui.components.SecondaryButton
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.ui.theme.TextPrimary
import com.paulscode.lightningfork.ui.theme.Warning
import androidx.compose.ui.res.stringResource
import com.paulscode.lightningfork.R

/** Why the phone must pair again; nothing is wiped until the user says so. */
@Composable
fun RemovedScreen(
    reason: com.paulscode.lightningfork.wallet.Repair,
    onPairAgain: () -> Unit,
    onTryAgain: () -> Unit,
) {
    val (title, text) = when (reason) {
        com.paulscode.lightningfork.wallet.Repair.Removed ->
            stringResource(R.string.pair_removed_title) to stringResource(R.string.pair_removed_text)
        com.paulscode.lightningfork.wallet.Repair.KeyLost ->
            stringResource(R.string.pair_key_lost_title) to stringResource(R.string.pair_key_lost_text)
        com.paulscode.lightningfork.wallet.Repair.CertificateChanged ->
            stringResource(R.string.pair_certificate_changed_title) to stringResource(R.string.pair_certificate_changed_text)
    }
    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().navigationBarsPadding().padding(28.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(Warning.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = Warning, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = TextPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
        // A changed certificate may be the network, not the node: trying
        // again comes first there; pairing again wipes this phone's key.
        if (reason == com.paulscode.lightningfork.wallet.Repair.CertificateChanged) {
            PrimaryButton(stringResource(R.string.pair_try_again), onClick = onTryAgain, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            SecondaryButton(stringResource(R.string.pair_pair_again), onClick = onPairAgain, modifier = Modifier.fillMaxWidth())
        } else {
            PrimaryButton(stringResource(R.string.pair_pair_again), onClick = onPairAgain, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            SecondaryButton(stringResource(R.string.pair_try_again), onClick = onTryAgain, modifier = Modifier.fillMaxWidth())
        }
    }
}
