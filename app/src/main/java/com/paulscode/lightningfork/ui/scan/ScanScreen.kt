package com.paulscode.lightningfork.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.paulscode.lightningfork.ui.components.PrimaryButton
import com.paulscode.lightningfork.ui.components.SecondaryButton
import com.paulscode.lightningfork.ui.theme.Accent
import com.paulscode.lightningfork.ui.theme.Page
import com.paulscode.lightningfork.ui.theme.TextMuted
import com.paulscode.lightningfork.ui.theme.TextPrimary

/**
 * Full-screen scanning: the camera with a framed window, the torch, and paste
 * as the way round a code that won't scan. [onResult] gets each text the camera
 * reads (the caller may ignore one and keep scanning); [onPaste] the clipboard.
 */
@Composable
fun ScanScreen(
    title: String,
    hint: String,
    onResult: (String) -> Unit,
    onPaste: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            QrScanner(onDetected = onResult, modifier = Modifier.fillMaxSize(), onCamera = { camera = it })
            Viewfinder()
        } else {
            Column(
                Modifier.fillMaxSize().background(Page).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Camera access is off", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (asked) "Allow the camera to scan codes, or paste instead." else "Allow the camera to scan codes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.clip(CircleShape).background(Color(0x66000000))) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            if (granted && camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(
                    onClick = {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    },
                    modifier = Modifier.clip(CircleShape).background(Color(0x66000000)),
                ) {
                    Icon(
                        if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                        contentDescription = if (torch) "Torch off" else "Torch on",
                        tint = Color.White,
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (granted) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
            }
            if (onPaste != null) {
                SecondaryButton("Paste instead", onClick = onPaste, icon = Icons.Rounded.ContentPaste, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Darkens all but a rounded square in the middle, and frames it. */
@Composable
private fun Viewfinder() {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        val side = size.minDimension * 0.7f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2.3f
        drawRect(Color(0xAA02060F))
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = CornerRadius(36f, 36f),
            blendMode = BlendMode.Clear,
        )
        drawRoundRect(
            color = Accent,
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = CornerRadius(36f, 36f),
            style = Stroke(width = 5f),
        )
    }
}
