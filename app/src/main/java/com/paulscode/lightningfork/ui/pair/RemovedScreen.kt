package com.paulscode.lightningfork.ui.pair

import androidx.compose.foundation.background
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

/** The node no longer accepts this phone's key. Nothing is wiped until the user says so. */
@Composable
fun RemovedScreen(onPairAgain: () -> Unit, onTryAgain: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().navigationBarsPadding().padding(28.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
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
            Text("This phone was removed", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            Text(
                "Your node no longer accepts this phone's key. It was removed in the dashboard, or the dashboard's list of phones was reset. Your funds are on your node and are not affected.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
        PrimaryButton("Pair again", onClick = onPairAgain, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Try again", onClick = onTryAgain, modifier = Modifier.fillMaxWidth())
    }
}
