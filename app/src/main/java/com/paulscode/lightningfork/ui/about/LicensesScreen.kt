package com.paulscode.lightningfork.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paulscode.lightningfork.ui.components.TopBar
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.TextMuted

/** The notices and license texts of the software built into the app. */
@Composable
fun LicensesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val paragraphs = remember {
        runCatching { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }
            .getOrDefault("The license notices could not be read.")
            .split("\n\n")
    }
    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Open-source licenses", onBack = onClose)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            items(paragraphs) { p ->
                Text(
                    p,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                    color = TextMuted,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}
