package com.klausms.vpn.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.klausms.vpn.R
import com.klausms.vpn.ui.DeepLink
import com.klausms.vpn.ui.ImportText
import com.klausms.vpn.ui.QrDecoder
import com.klausms.vpn.ui.components.GlassIconButton
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads a QR code with a key or a subscription link through the back
 * camera and hands its text to [onFound] once. Everything stays on the
 * phone: the frames are decoded here and never stored. The camera is on
 * only while this screen is shown. [onNoPermission]: the screen came back
 * (after the app was closed) without the camera permission, which the
 * user can take away in Settings meanwhile.
 */
@Composable
fun ScanScreen(onBack: () -> Unit, onFound: (String) -> Unit, onNoPermission: () -> Unit = onBack) {
    val context = LocalContext.current
    val granted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    if (!granted) {
        LaunchedEffect(Unit) { onNoPermission() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    CameraScanner(onBack, onFound)
}

@Composable
private fun CameraScanner(onBack: () -> Unit, onFound: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val found by rememberUpdatedState(onFound)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var failed by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    // A code that is not a key: said for a moment, scanning goes on.
    var notAKey by remember { mutableIntStateOf(0) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner) {
        val analysisThread = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val done = AtomicBoolean(false)
        var lastRejected: String? = null
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(
            analysisThread,
            QrAnalyzer { text ->
                if (done.get()) return@QrAnalyzer
                // "Add to Kirov VPN" links carry the key inside.
                val payload = DeepLink.payload(text) ?: text.trim()
                if (ImportText.links(payload).isEmpty()) {
                    if (payload != lastRejected) {
                        lastRejected = payload
                        main.execute { notAKey++ }
                    }
                } else if (done.compareAndSet(false, true)) {
                    main.execute { found(payload) }
                }
            },
        )
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        var provider: ProcessCameraProvider? = null
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (disposed) return@addListener
                try {
                    val p = future.get()
                    provider = p
                    p.unbindAll()
                    // The back camera; a tablet or laptop may only have a front one.
                    val selector = when {
                        p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> null
                    }
                    if (selector == null) {
                        failed = true
                    } else {
                        val cam = p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                        camera = cam
                        // Opening fails later, not in bindToLifecycle (camera
                        // disabled by policy, taken by another app): say so
                        // instead of a black screen.
                        cam.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
                            val error = state.error
                            if (error != null && error.type == CameraState.ErrorType.CRITICAL) {
                                AppLog.w("camera error ${error.code}")
                                failed = true
                            } else if (state.type == CameraState.Type.OPEN) {
                                failed = false
                            }
                        }
                        // The torch goes off when the app leaves the screen.
                        cam.cameraInfo.torchState.observe(lifecycleOwner) { torch = it == TorchState.ON }
                    }
                } catch (e: Exception) {
                    AppLog.w("camera did not start", e)
                    failed = true
                }
            },
            main,
        )
        onDispose {
            disposed = true
            done.set(true)
            camera?.cameraInfo?.let { info ->
                info.cameraState.removeObservers(lifecycleOwner)
                info.torchState.removeObservers(lifecycleOwner)
            }
            camera = null
            try {
                provider?.unbindAll()
            } catch (e: Exception) {
                AppLog.w("camera did not stop", e)
            }
            analysis.clearAnalyzer()
            analysisThread.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Dim everything but the square the code should go in.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = minOf(maxWidth, maxHeight) * 0.68f
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                val s = side.toPx()
                val topLeft = Offset((size.width - s) / 2, (size.height - s) / 2 - 24.dp.toPx())
                val radius = CornerRadius(28.dp.toPx())
                drawRect(Color(0x99000000))
                drawRoundRect(Color.Black, topLeft, GeometrySize(s, s), radius, blendMode = BlendMode.Clear)
                drawRoundRect(Color.White, topLeft, GeometrySize(s, s), radius, style = Stroke(3.dp.toPx()))
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Box(Modifier.align(Alignment.CenterStart)) {
                GlassIconButton(R.drawable.ic_close_ios, "Закрыть", onClick = onBack, iconSize = 16.dp)
            }
            Text(
                "QR-код",
                style = IosType.headline,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            val cam = camera
            if (cam != null && cam.cameraInfo.hasFlashUnit()) {
                Box(Modifier.align(Alignment.CenterEnd)) {
                    GlassIconButton(
                        R.drawable.ic_flash_ios,
                        if (torch) "Выключить фонарик" else "Включить фонарик",
                        onClick = {
                            torch = !torch
                            cam.cameraControl.enableTorch(torch)
                        },
                        tint = if (torch) Color(0xFFFFD60A) else Color.White,
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 32.dp, end = 32.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var hint by remember { mutableStateOf(false) }
            LaunchedEffect(notAKey) {
                if (notAKey > 0) {
                    hint = true
                    delay(2500)
                    hint = false
                }
            }
            Text(
                when {
                    failed -> "Камера недоступна. Скопируйте ключ и вставьте его из буфера."
                    hint -> "Это не ключ и не ссылка на подписку"
                    else -> "Наведите камеру на QR-код с ключом или ссылкой на подписку"
                },
                style = IosType.subhead,
                color = if (failed || hint) kc.orange else Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Hands every frame's brightness plane to the decoder; a found text goes to [onText]. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val decoder = QrDecoder()
    private var luma = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val size = buffer.remaining()
            if (luma.size != size) luma = ByteArray(size)
            buffer.get(luma)
            val crop = image.cropRect
            decoder.decode(luma, plane.rowStride, crop.left, crop.top, crop.width(), crop.height())?.let(onText)
        } catch (e: Exception) {
            // A frame that could not be read; the next one comes right away.
        } finally {
            image.close()
        }
    }
}
