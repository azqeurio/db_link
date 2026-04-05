package dev.pl36.cameralink

import android.app.Application
import dev.pl36.cameralink.core.deepsky.DeepSkyLiveStackCoordinator
import dev.pl36.cameralink.core.usb.OmCaptureUsbManager

class CameraLinkAppContainer(
    application: Application,
) {
    val deepSkyLiveStackCoordinator: DeepSkyLiveStackCoordinator =
        DeepSkyLiveStackCoordinator(application.applicationContext)
    val omCaptureUsbManager: OmCaptureUsbManager =
        OmCaptureUsbManager(application.applicationContext)
}
