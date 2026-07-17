package dev.dblink.core.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * How downloaded photos are organised into sub-folders underneath the user's
 * chosen base save location (e.g. `Pictures/db link`).
 *
 * The chosen [subfolder] is appended to the base path, so a file captured on
 * 2026-06-25 ends up at, for example, `Pictures/db link/2026-06-25/_6250001.JPG`
 * for [DateDay], or directly at `Pictures/db link/_6250001.JPG` for [Flat].
 */
enum class DownloadFolderStructure(val preferenceValue: String) {
    /** One folder per capture day: `2026-06-25`. */
    DateDay("date_day"),

    /** Year then month: `2026/06`. */
    DateMonth("date_month"),

    /** One folder per year: `2026`. */
    DateYear("date_year"),

    /** No sub-folders — everything goes straight into the base location. */
    Flat("flat"),
    ;

    /**
     * Relative sub-folder for an image captured on [captureDate] (may be null
     * when the date is unknown). Returns "" for [Flat] / unknown dates with a
     * flat layout, never a leading/trailing slash.
     */
    fun subfolder(captureDate: LocalDate?): String {
        if (this == Flat) return ""
        if (captureDate == null) return UNSORTED_FOLDER
        return when (this) {
            DateDay -> captureDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            DateMonth -> "%04d/%02d".format(captureDate.year, captureDate.monthValue)
            DateYear -> captureDate.year.toString()
            Flat -> ""
        }
    }

    companion object {
        const val UNSORTED_FOLDER = "Unsorted"

        fun fromPreferenceValue(value: String?): DownloadFolderStructure =
            entries.firstOrNull { it.preferenceValue == value } ?: DateDay
    }
}
