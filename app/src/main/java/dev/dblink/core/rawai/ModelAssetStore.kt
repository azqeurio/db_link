package dev.dblink.core.rawai

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Stages immutable model assets into versioned app-internal storage. */
object ModelAssetStore {
    fun stage(context: Context, assetFileName: String, expectedSha256: String): File {
        require(assetFileName == File(assetFileName).name) { "Model asset must be a filename: $assetFileName" }
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Invalid expected SHA-256 for $assetFileName: $expectedSha256"
        }

        val normalizedSha = expectedSha256.lowercase()
        val destinationDirectory = File(context.filesDir, "raw_ai/models/$normalizedSha")
        check(destinationDirectory.mkdirs() || destinationDirectory.isDirectory) {
            "Cannot create model directory: ${destinationDirectory.absolutePath}"
        }
        val destination = File(destinationDirectory, assetFileName)
        if (destination.isFile && sha256(destination) == normalizedSha) return destination

        val partial = File(destinationDirectory, "$assetFileName.partial")
        if (partial.exists() && !partial.delete()) {
            error("Cannot remove partial model copy: ${partial.absolutePath}")
        }

        try {
            context.assets.open("raw_ai/$assetFileName").use { input ->
                partial.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            val actualSha = sha256(partial)
            check(actualSha == normalizedSha) {
                "Model SHA-256 mismatch for $assetFileName: expected=$normalizedSha actual=$actualSha " +
                    "path=${partial.absolutePath}"
            }
            if (destination.exists() && !destination.delete()) {
                error("Cannot replace invalid model: ${destination.absolutePath}")
            }
            check(partial.renameTo(destination)) {
                "Atomic model install failed: ${partial.absolutePath} -> ${destination.absolutePath}"
            }
            check(sha256(destination) == normalizedSha) {
                "Installed model failed final SHA-256 verification: ${destination.absolutePath}"
            }
            return destination
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    fun sha256(file: File): String = file.inputStream().use(::sha256)

    fun sha256(bytes: ByteArray): String = bytes.inputStream().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
