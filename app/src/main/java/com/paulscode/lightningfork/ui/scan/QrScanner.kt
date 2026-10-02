package com.paulscode.lightningfork.ui.scan

import android.util.Log
import com.paulscode.lightningfork.BuildConfig
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * CameraX preview with an ML Kit QR analyzer, emitting each decoded text via
 * [onDetected]; the caller decides what it is. [onCamera] hands over the
 * camera, for the torch.
 */
@Composable
fun QrScanner(
    onDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCamera: (Camera) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    val bound = remember { mutableListOf<Pair<ProcessCameraProvider, Array<androidx.camera.core.UseCase>>>() }
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            // The camera is bound to the activity, which outlives this screen:
            // let it go now, or it stays on behind Review and Sending.
            bound.forEach { (provider, cases) -> runCatching { provider.unbind(*cases) } }
            bound.clear()
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                // Closed before the camera was ready: don't bind it now.
                if (disposed.get()) return@addListener
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(executor) { proxy ->
                            if (executor.isShutdown) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            val image = proxy.image
                            if (image == null) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
                            scanner.process(input)
                                .addOnSuccessListener { codes ->
                                    codes.firstOrNull()?.rawValue?.let { text ->
                                        ContextCompat.getMainExecutor(ctx).execute { onDetected(text) }
                                    }
                                }
                                .addOnFailureListener { if (BuildConfig.DEBUG) Log.w("QrScanner", "scan failed", it) }
                                .addOnCompleteListener { proxy.close() }
                        }
                    }
                runCatching {
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                    bound.add(provider to arrayOf(preview, analysis))
                    onCamera(camera)
                }.onFailure { if (BuildConfig.DEBUG) Log.e("QrScanner", "bind failed", it) }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}
