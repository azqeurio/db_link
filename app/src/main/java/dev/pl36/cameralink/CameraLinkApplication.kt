package dev.pl36.cameralink

import android.app.Application
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.logging.FileLogger

class CameraLinkApplication : Application() {
    lateinit var appContainer: CameraLinkAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Initialize the Pro-level File Logger
        FileLogger.initialize(this)
        appContainer = CameraLinkAppContainer(this)
        D.lifecycle("Application started. Environment: ${if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"}")
    }
}
