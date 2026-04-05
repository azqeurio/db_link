package dev.pl36.cameralink.core.protocol

import dev.pl36.cameralink.core.logging.D
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses the response from get_imglist.cgi.
 *
 * Response format (one file per line, comma-separated fields):
 *   /DCIM/100OLYMP,P1010001.JPG,12345678,0,4096,2160
 *   /DCIM/100OMSYS,_3140694.JPG,13245934,0,23642,11512
 *
 * Fields: directory,filename,size,attribute,width,height
 *
 * Olympus filename convention: _MDDNNNN.EXT
 *   M   = month (1-9=Jan-Sep, A=Oct, B=Nov, C=Dec)
 *   DD  = day of month (01-31)
 *   NNNN = sequence number
 */
data class CameraImage(
    val directory: String,
    val fileName: String,
    val fileSize: Long,
    val attribute: Int,
    val width: Int,
    val height: Int,
) {
    /** Full path for CGI requests: /DCIM/100OLYMP/P1010001.JPG */
    val fullPath: String get() = "$directory/$fileName"

    /** Attribute bit 4 (value 16) = directory entry */
    val isDirectory: Boolean get() = (attribute and 16) != 0

    val isRaw: Boolean get() = fileName.uppercase().let {
        it.endsWith(".ORF") || it.endsWith(".RAW")
    }

    val isMovie: Boolean get() = fileName.uppercase().let {
        it.endsWith(".MOV") || it.endsWith(".MP4") || it.endsWith(".AVI")
    }

    val isJpeg: Boolean get() = fileName.uppercase().let {
        it.endsWith(".JPG") || it.endsWith(".JPEG")
    }

    /**
     * Parse capture date from Olympus filename convention _MDDNNNN.EXT.
     * Returns a [LocalDate] for grouping and display, or null if the name
     * doesn't follow the convention.
     */
    val captureDate: LocalDate? by lazy {
        val name = fileName.substringBeforeLast('.')
        // Expect _MDDNNNN (8 chars including underscore)
        if (name.length < 4 || name[0] != '_') return@lazy null

        val monthChar = name[1]
        val month = when {
            monthChar in '1'..'9' -> monthChar.digitToInt()
            monthChar.uppercaseChar() == 'A' -> 10
            monthChar.uppercaseChar() == 'B' -> 11
            monthChar.uppercaseChar() == 'C' -> 12
            else -> return@lazy null
        }
        val day = name.substring(2, 4).toIntOrNull() ?: return@lazy null
        if (day < 1 || day > 31) return@lazy null

        // Infer year: if this month-day is in the future, assume last year
        val today = LocalDate.now()
        val year = if (month > today.monthValue ||
            (month == today.monthValue && day > today.dayOfMonth)
        ) {
            today.year - 1
        } else {
            today.year
        }

        try {
            LocalDate.of(year, month, day)
        } catch (_: Exception) {
            null
        }
    }

    /** Human-readable date label for section headers */
    val captureDateLabel: String
        get() {
            val date = captureDate ?: return if (Locale.getDefault().language == "ko") "기타" else "Other"
            val pattern = if (Locale.getDefault().language == "ko") {
                "yyyy년 M월 d일 (E)"
            } else {
                "MMM d, yyyy (E)"
            }
            return date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }

    /** Sortable date key for grouping: "2026-03-14" or "0000-00-00" */
    val captureDateKey: String
        get() = captureDate?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: "0000-00-00"

    /** Folder name for date-based download: "2026-03-14" */
    val dateFolderName: String
        get() = captureDate?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: "Unsorted"
}

object ImageListParser {

    /**
     * Parse the get_imglist.cgi response body into a list of [CameraImage].
     * Handles both the "VER_100" header format and raw listing format.
     */
    fun parse(response: String): List<CameraImage> {
        val images = mutableListOf<CameraImage>()

        response.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("VER_")) return@forEach

            val parts = trimmed.split(",")
            if (parts.size >= 2) {
                try {
                    val directory = parts[0].trim()
                    val fileName = parts[1].trim()
                    val fileSize = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                    val attribute = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
                    val width = parts.getOrNull(4)?.trim()?.toIntOrNull() ?: 0
                    val height = parts.getOrNull(5)?.trim()?.toIntOrNull() ?: 0

                    images.add(
                        CameraImage(
                            directory = directory,
                            fileName = fileName,
                            fileSize = fileSize,
                            attribute = attribute,
                            width = width,
                            height = height,
                        )
                    )
                } catch (e: Exception) {
                    D.transfer("Failed to parse image line: $trimmed - ${e.message}")
                }
            }
        }

        D.transfer("Parsed ${images.size} images from response")
        return images
    }
}
