package dev.dblink.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AppReleaseInfo(
    val versionName: String,
    val assetFileName: String,
    val downloadUrl: String,
)

class GitHubAppUpdateManager(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) {
    companion object {
        private const val LatestReleaseUrl = "https://api.github.com/repos/azqeurio/db_link/releases/latest"
    }

    suspend fun fetchLatestRelease(): AppReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LatestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "db-link-android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub releases API returned HTTP ${response.code}")
            }
            val responseBody = response.body.string()
            val json = JSONObject(responseBody)
            val rawVersion = json.optString("tag_name")
                .ifBlank { json.optString("name") }
                .ifBlank { throw IOException("Latest release is missing a version tag") }
            val asset = selectApkAsset(json)
                ?: throw IOException("Latest GitHub release does not contain a release APK")
            AppReleaseInfo(
                versionName = rawVersion.removePrefix("v").removePrefix("V"),
                assetFileName = asset.first,
                downloadUrl = asset.second,
            )
        }
    }

    suspend fun downloadReleaseApk(release: AppReleaseInfo): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updatesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) && !it.name.equals(release.assetFileName, ignoreCase = true) }
            ?.forEach { it.delete() }
        val targetFile = File(updatesDir, release.assetFileName)
        val request = Request.Builder()
            .url(release.downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "db-link-android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("APK download failed with HTTP ${response.code}")
            }
            val body = response.body
            targetFile.outputStream().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }
        targetFile
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun buildUnknownSourcesIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
    }

    fun buildInstallIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun selectApkAsset(json: JSONObject): Pair<String, String>? {
        val assets = json.optJSONArray("assets") ?: return null
        var fallback: Pair<String, String>? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) {
                continue
            }
            val pair = name to url
            if (!name.contains("debug", ignoreCase = true) && name.contains("release", ignoreCase = true)) {
                return pair
            }
            if (!name.contains("debug", ignoreCase = true)) {
                fallback = fallback ?: pair
            }
            if (fallback == null) {
                fallback = pair
            }
        }
        return fallback
    }
}
