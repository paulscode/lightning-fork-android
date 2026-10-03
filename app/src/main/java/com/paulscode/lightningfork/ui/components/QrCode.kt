package com.paulscode.lightningfork.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.ui.theme.BitcoinGradient
import com.paulscode.lightningfork.ui.theme.LightningGradient
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private class Matrix(val size: Int, val dark: BooleanArray)

private fun encode(content: String): Matrix {
    // H leaves room for the bolt in the middle.
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.MARGIN to 0,
    )
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val n = m.width
    val dark = BooleanArray(n * n) { i -> m.get(i % n, i / n) }
    return Matrix(n, dark)
}

/**
 * A QR code, dark modules on white so any scanner reads it, with a quiet
 * zone and the bolt in the middle.
 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, logo: Boolean = true, bitcoin: Boolean = false) {
    val matrix = remember(content) { runCatching { encode(content) }.getOrNull() }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix != null) {
            Canvas(Modifier.fillMaxSize()) {
                // Whole-pixel squares: a module drawn at a fractional size
                // leaves light seams that some scanners read as gaps.
                val n = matrix.size
                val cell = kotlin.math.floor(size.minDimension / n)
                val origin = ((size.minDimension - cell * n) / 2f)
                val ink = Color(0xFF0A1128)
                for (y in 0 until n) {
                    var x = 0
                    while (x < n) {
                        if (!matrix.dark[y * n + x]) {
                            x++
                            continue
                        }
                        var run = 1
                        while (x + run < n && matrix.dark[y * n + x + run]) run++
                        drawRect(
                            color = ink,
                            topLeft = Offset(origin + x * cell, origin + y * cell),
                            size = Size(cell * run, cell),
                        )
                        x += run
                    }
                }
            }
            if (logo) {
                Box(
                    Modifier
                        .fillMaxWidth(0.2f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .padding(3.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (bitcoin) BitcoinGradient else LightningGradient),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitcoin) {
                            Image(
                                painterResource(R.drawable.ic_bitcoin),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(Color.White),
                                modifier = Modifier.fillMaxSize(0.62f),
                            )
                        } else {
                            Image(
                                painterResource(R.drawable.ic_bolt),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(Color.White),
                                modifier = Modifier.fillMaxSize(0.62f),
                            )
                        }
                    }
                }
            }
        }
    }
}

