package dev.dblink

import android.app.Application
import dev.dblink.core.deepsky.DeepSkyLiveStackCoordinator
import dev.dblink.core.usb.OmCaptureUsbManager

class DbLinkAppContainer(
    application: Application,
) {
    val deepSkyLiveStackCoordinator: DeepSkyLiveStackCoordinator =
        DeepSkyLiveStackCoordinator(application.applicationContext)
    val omCaptureUsbManager: OmCaptureUsbManager =
        OmCaptureUsbManager(application.applicationContext)
}
