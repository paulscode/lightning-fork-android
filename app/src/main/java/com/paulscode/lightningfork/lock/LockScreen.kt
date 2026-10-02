package com.paulscode.lightningfork.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.ui.components.PrimaryButton
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.ui.theme.TextPrimary

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Page)) {
        Image(
            painterResource(R.drawable.storm),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.35f),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Page.copy(alpha = 0.3f), Page))))
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(84.dp).clip(CircleShape)) {
                Image(painterResource(R.drawable.storm), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(22.dp))
            Text("Lightning Fork is locked", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text("Unlock to see your balances and send.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Unlock", onClick = onUnlock, icon = Icons.Rounded.Lock, modifier = Modifier.fillMaxWidth())
        }
    }
}
