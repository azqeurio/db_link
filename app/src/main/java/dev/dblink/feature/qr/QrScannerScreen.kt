@file:androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])

package dev.dblink.feature.qr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.dblink.core.logging.D
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
fun QrScannerScreen(
    onCredentialsFound: (WifiCredentials) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCredentialsFound by rememberUpdatedState(onCredentialsFound)
    val deviceHasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    var hasCameraPermission by remember(context) { mutableStateOf(cameraPermissionGranted(context)) }
    var permissionPromptShown by rememberSaveable { mutableStateOf(false) }
    var permissionRequestInFlight by rememberSaveable { mutableStateOf(false) }
    var scannerErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var previewViewReady by remember { mutableStateOf(false) }
    var lifecycleResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val bindGeneration = remember { AtomicLong(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequestInFlight = false
        hasCameraPermission = granted
        D.qr("QR camera permission result: granted=$granted")
        if (granted) {
            scannerErrorMessage = null
        }
    }

    fun requestCameraPermission(reason: String) {
        if (permissionRequestInFlight) {
            D.qr("Skipping duplicate QR permission request: reason=$reason")
            return
        }
        permissionPromptShown = true
        permissionRequestInFlight = true
        D.qr("Requesting QR camera permission: reason=$reason")
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner, context) {
        fun syncLifecycleState() {
            val resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (lifecycleResumed != resumed) {
                D.qr("QR lifecycle resumed=$resumed")
            }
            lifecycleResumed = resumed

            val granted = cameraPermissionGranted(context)
            if (hasCameraPermission != granted) {
                D.qr("QR camera permission state changed: granted=$granted")
            }
            hasCameraPermission = granted
            if (granted) {
                permissionRequestInFlight = false
            }
        }

        syncLifecycleState()
        val observer = LifecycleEventObserver { _, _ ->
            syncLifecycleState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleResumed = false
        }
    }

    LaunchedEffect(deviceHasCamera) {
        D.qr("QR device camera availability: available=$deviceHasCamera")
        if (!deviceHasCamera) {
            D.err("QR", "QR scanner unavailable: no device camera detected")
        }
    }

    LaunchedEffect(hasCameraPermission) {
        D.qr("QR permission snapshot: granted=$hasCameraPermission")
    }

    LaunchedEffect(deviceHasCamera, hasCameraPermission, permissionPromptShown, permissionRequestInFlight) {
        if (!deviceHasCamera) {
            return@LaunchedEffect
        }
        if (!hasCameraPermission && !permissionPromptShown && !permissionRequestInFlight) {
            requestCameraPermission(reason = "initial_open")
        }
    }

    val scannerOptions = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    }

    DisposableEffect(previewView) {
        val view = previewView
        if (view == null) {
            onDispose { }
        } else {
            fun updatePreviewReadiness(trigger: String) {
                val ready = isPreviewViewReady(view)
                if (previewViewReady != ready) {
                    D.qr(
                        "QR preview readiness changed: ready=$ready, trigger=$trigger, " +
                            "attached=${view.isAttachedToWindow}, display=${view.display != null}",
                    )
                }
                previewViewReady = ready
            }

            val attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    updatePreviewReadiness(trigger = "attached")
                }

                override fun onViewDetachedFromWindow(v: View) {
                    updatePreviewReadiness(trigger = "detached")
                }
            }

            updatePreviewReadiness(trigger = "effect_start")
            view.addOnAttachStateChangeListener(attachListener)
            onDispose {
                if (previewView === view) {
                    previewViewReady = false
                }
                view.removeOnAttachStateChangeListener(attachListener)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (deviceHasCamera && hasCameraPermission && scannerErrorMessage == null) {
            AndroidView(
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { view ->
                    if (previewView !== view) {
                        previewView = view
                        D.qr("QR preview view attached to composition")
                    }
                    val ready = isPreviewViewReady(view)
                    if (previewViewReady != ready) {
                        D.qr("QR preview readiness changed: ready=$ready, trigger=android_view_update")
                    }
                    previewViewReady = ready
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        when {
            !deviceHasCamera -> {
                QrScannerStatusPanel(
                    title = "No camera available",
                    message = "This device does not report any camera hardware, so QR scanning cannot start.",
                    secondaryActionLabel = "Open app settings",
                    onSecondaryAction = { openAppSettings(context) },
                )
            }

            !hasCameraPermission -> {
                val message = when {
                    permissionRequestInFlight -> "Requesting camera access so QR scanning can start."
                    shouldShowCameraPermissionRationale(context) ->
                        "Camera access is required to scan the camera's QR code."
                    permissionPromptShown ->
                        "Camera access is still blocked. Grant access, or open app settings if Android is no longer showing the permission prompt."
                    else -> "Camera access is required to scan the camera's QR code."
                }
                QrScannerStatusPanel(
                    title = "Camera permission required",
                    message = message,
                    primaryActionLabel = "Grant camera access",
                    onPrimaryAction = { requestCameraPermission(reason = "manual_retry") },
                    secondaryActionLabel = "Open app settings",
                    onSecondaryAction = { openAppSettings(context) },
                )
            }

            scannerErrorMessage != null -> {
                QrScannerStatusPanel(
                    title = "QR scanner unavailable",
                    message = scannerErrorMessage.orEmpty(),
                    primaryActionLabel = "Retry scanner",
                    onPrimaryAction = {
                        D.qr("Manual QR scanner retry requested")
                        scannerErrorMessage = null
                    },
                    secondaryActionLabel = "Open app settings",
                    onSecondaryAction = { openAppSettings(context) },
                )
            }
        }
    }

    DisposableEffect(
        deviceHasCamera,
        hasCameraPermission,
        lifecycleOwner,
        previewView,
        previewViewReady,
        lifecycleResumed,
        scannerErrorMessage,
    ) {
        val view = previewView
        if (view == null) {
            onDispose { }
        } else {
            val boundView = view
            val canBind =
                deviceHasCamera &&
                    hasCameraPermission &&
                    scannerErrorMessage == null &&
                    lifecycleResumed &&
                    previewViewReady &&
                    isPreviewViewReady(boundView)

            if (!canBind) {
                onDispose { }
            } else {
                val generation = bindGeneration.incrementAndGet()
                var disposed = false
                var cleanedUp = false
                var scanner: BarcodeScanner? = null
                var analysisExecutor: ExecutorService? = null
                var controller: LifecycleCameraController? = null

                fun cleanup(reason: String) {
                    if (cleanedUp) {
                        return
                    }
                    cleanedUp = true
                    disposed = true
                    bindGeneration.incrementAndGet()
                    D.qr("QR scanner cleanup: reason=$reason, generation=$generation")
                    if (previewView === boundView) {
                        previewViewReady = false
                    }
                    runCatching { boundView.controller = null }
                        .onFailure { D.err("QR", "Failed to detach preview controller", it) }
                    runCatching { controller?.clearImageAnalysisAnalyzer() }
                        .onFailure { D.err("QR", "Failed to clear QR analyzer", it) }
                    runCatching { controller?.unbind() }
                        .onFailure { D.err("QR", "Failed to unbind QR camera controller", it) }
                    runCatching { scanner?.close() }
                        .onFailure { D.err("QR", "Failed to close QR barcode scanner", it) }
                    analysisExecutor?.shutdownNow()
                }

                runCatching {
                    D.qr(
                        "QR scanner bind attempt: generation=$generation, lifecycleResumed=$lifecycleResumed, " +
                            "previewReady=$previewViewReady",
                    )
                    val liveScanner = BarcodeScanning.getClient(scannerOptions)
                    scanner = liveScanner
                    val liveExecutor = Executors.newSingleThreadExecutor()
                    analysisExecutor = liveExecutor
                    val liveController = LifecycleCameraController(boundView.context).apply {
                        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    controller = liveController

                    liveController.setImageAnalysisAnalyzer(liveExecutor) { imageProxy ->
                        if (disposed || generation != bindGeneration.get() || scanned) {
                            imageProxy.close()
                            return@setImageAnalysisAnalyzer
                        }

                        val mediaImage = getMediaImage(imageProxy)
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setImageAnalysisAnalyzer
                        }

                        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        try {
                            liveScanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    if (disposed || generation != bindGeneration.get() || scanned) {
                                        return@addOnSuccessListener
                                    }
                                    for (barcode in barcodes) {
                                        val rawValue = barcode.rawValue ?: continue
                                        D.qr("QrScannerScreen: Detected barcode, rawValue=$rawValue")
                                        val credentials = WifiQrParser.parse(rawValue)
                                        if (credentials != null && !scanned) {
                                            scanned = true
                                            liveController.clearImageAnalysisAnalyzer()
                                            D.qr(
                                                "QrScannerScreen: Parsed credentials successfully. " +
                                                    "Invoking onCredentialsFound.",
                                            )
                                            currentOnCredentialsFound(credentials)
                                            break
                                        }
                                    }
                                }
                                .addOnFailureListener { error ->
                                    if (!disposed && generation == bindGeneration.get()) {
                                        D.err("QR", "QR frame analysis failed", error)
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } catch (throwable: Throwable) {
                            D.err("QR", "Failed to submit QR frame to ML Kit", throwable)
                            imageProxy.close()
                        }
                    }

                    if (!isPreviewViewReady(boundView)) {
                        error("Preview surface was detached before CameraX bind.")
                    }

                    liveController.bindToLifecycle(lifecycleOwner)
                    boundView.controller = liveController
                    D.qr("QR scanner bind success: generation=$generation")
                }.onFailure { throwable ->
                    D.err("QR", "QR scanner startup failed", throwable)
                    scannerErrorMessage = scannerStartupMessage(throwable)
                    cleanup(reason = "startup_failure")
                }

                onDispose {
                    cleanup(reason = "dispose")
                }
            }
        }
    }
}

@ExperimentalGetImage
private fun getMediaImage(imageProxy: ImageProxy) = imageProxy.image

@Composable
private fun QrScannerStatusPanel(
    title: String,
    message: String,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Button(onClick = onPrimaryAction) {
                    Text(primaryActionLabel)
                }
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                OutlinedButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}

private fun cameraPermissionGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun isPreviewViewReady(view: PreviewView): Boolean {
    return view.isAttachedToWindow && view.windowToken != null && view.display != null
}

private fun scannerStartupMessage(throwable: Throwable): String {
    return when (throwable) {
        is SecurityException -> "Camera access was denied. Grant camera access and try again."
        is IllegalStateException ->
            "The camera could not start on this device right now. Retry once, or reopen app settings if it keeps failing."
        else -> throwable.message?.takeIf { it.isNotBlank() }
            ?: "The QR camera could not start. Retry once, or reopen app settings if it keeps failing."
    }
}

private fun shouldShowCameraPermissionRationale(context: Context): Boolean {
    val activity = context.findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
