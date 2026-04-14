# Application-specific R8 rules.
-keep class dev.dblink.core.protocol.** { *; }
-keep class dev.dblink.core.model.** { *; }
-keep class dev.dblink.core.usb.UsbNative { *; }

-keep class androidx.camera.view.PreviewView { *; }
-keep class androidx.camera.view.CameraController { *; }
-keep class androidx.camera.view.LifecycleCameraController { *; }

-keep class com.google.mlkit.vision.barcode.BarcodeScannerOptions { *; }
-keep class com.google.mlkit.vision.barcode.BarcodeScanning { *; }
-keep class com.google.mlkit.vision.barcode.BarcodeScanner { *; }
-keep class com.google.mlkit.vision.barcode.common.Barcode { *; }
-keep class com.google.mlkit.vision.barcode.internal.** { *; }
-keep class com.google.mlkit.vision.common.InputImage { *; }
-keep class com.google.mlkit.vision.common.internal.** { *; }
-keep class com.google.mlkit.common.internal.** { *; }
-keep class com.google.mlkit.common.sdkinternal.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
