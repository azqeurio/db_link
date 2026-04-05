package dev.pl36.cameralink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.pl36.cameralink.core.localization.AppLanguageManager
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.logging.FileLogger

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        D.lifecycle("MainActivity.onCreate savedState=${savedInstanceState != null}")
        enableEdgeToEdge()
        setContent {
            CameraLinkApp(
                onExportLogs = { exportLogFile() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        D.lifecycle("MainActivity.onResume")
    }

    override fun onPause() {
        D.lifecycle("MainActivity.onPause")
        super.onPause()
    }

    override fun onDestroy() {
        D.lifecycle("MainActivity.onDestroy isFinishing=$isFinishing")
        super.onDestroy()
    }

    private fun exportLogFile() {
        D.ui("Export logs requested")
        val logFiles = FileLogger.getAllLogFiles().filter { it.exists() }
        if (logFiles.isEmpty()) {
            D.ui("Export aborted: log file not found")
            return
        }

        try {
            val uris = ArrayList<android.net.Uri>(logFiles.size)
            logFiles.forEach { logFile ->
                uris += androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    logFile,
                )
            }
            val intent = if (uris.size == 1) {
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/plain"
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            D.ui(
                "Launching log export chooser, files=${logFiles.size}, " +
                    "totalSize=${logFiles.sumOf { it.length() }} bytes",
            )
            startActivity(android.content.Intent.createChooser(intent, "Export Logs"))
        } catch (e: Exception) {
            D.err("UI", "Export log failed", e)
        }
    }
}
