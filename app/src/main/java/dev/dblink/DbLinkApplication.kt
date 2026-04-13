package dev.dblink

import android.app.Application
import dev.dblink.core.logging.D
import dev.dblink.core.logging.FileLogger

class DbLinkApplication : Application() {
    lateinit var appContainer: DbLinkAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Initialize local file logging before the app starts work.
        FileLogger.initialize(this)
        appContainer = DbLinkAppContainer(this)
        D.lifecycle("Application started. Environment: ${if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"}")
    }
}
