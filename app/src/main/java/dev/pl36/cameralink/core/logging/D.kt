package dev.pl36.cameralink.core.logging

import java.util.concurrent.ConcurrentHashMap

/**
 * Debug logger for maximum log coverage.
 *
 * Usage: D.wifi("Connected to camera"), D.ble("Scan started"), etc.
 *
 * To remove all debug logs later:
 *   1. Delete all lines containing "D." calls
 *   2. Delete all "import ...logging.D" lines
 *   3. Delete this file (D.kt)
 *
 * grep -rn "^\s*D\." --include="*.kt" src/   # preview
 * sed -i '/^\s*D\./d' $(find src -name "*.kt") # remove calls
 * sed -i '/import.*\.logging\.D$/d' $(find src -name "*.kt") # remove imports
 */
object D {
    @Volatile
    var enabled: Boolean = true

    /**
     * When true, only USB/PTP-related categories are logged.
     * All other categories (WIFI, BLE, HTTP, UI, etc.) are suppressed.
     * Toggle this off when debugging non-tethering features.
     */
    @Volatile
    var usbTetherFocus: Boolean = true

    /** Disable specific categories: D.disabledCategories.add("HTTP") */
    val disabledCategories: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Ongoing timers for measuring elapsed durations. */
    private val timers = ConcurrentHashMap<String, Long>()

    /** Categories that are always logged regardless of [usbTetherFocus]. */
    private val tetherCategories = setOf("USB", "PTP", "PERM", "LIFECYCLE", "ASTRO", "STACK", "RAW", "ACTION", "NAV")

    // ── Category loggers ──────────────────────────────────────

    fun wifi(msg: String) = emit("WIFI", msg)
    fun ble(msg: String) = emit("BLE", msg)
    fun http(msg: String) = emit("HTTP", msg)
    fun proto(msg: String) = emit("PROTO", msg)
    fun ui(msg: String) = emit("UI", msg)
    fun nav(msg: String) = emit("NAV", msg)
    fun perm(msg: String) = emit("PERM", msg)
    fun geo(msg: String) = emit("GEO", msg)
    fun session(msg: String) = emit("SESSION", msg)
    fun pref(msg: String) = emit("PREF", msg)
    fun lifecycle(msg: String) = emit("LIFECYCLE", msg)
    fun qr(msg: String) = emit("QR", msg)
    fun stream(msg: String) = emit("STREAM", msg)
    fun transfer(msg: String) = emit("TRANSFER", msg)
    fun reconnect(msg: String) = emit("RECONNECT", msg)
    fun mode(msg: String) = emit("MODE", msg)
    fun prop(msg: String) = emit("PROP", msg)
    fun exposure(msg: String) = emit("EXPOSURE", msg)
    fun cmd(msg: String) = emit("CMD", msg)
    fun layout(msg: String) = emit("LAYOUT", msg)
    fun dial(msg: String) = emit("DIAL", msg)
    fun usb(msg: String) = emit("USB", msg)
    fun ptp(msg: String) = emit("PTP", msg)
    fun astro(msg: String) = emit("ASTRO", msg)
    fun stack(msg: String) = emit("STACK", msg)
    fun raw(msg: String) = emit("RAW", msg)

    /**
     * User-initiated action log — always logged regardless of focus mode.
     * Captures what the user tapped/dragged, and what the app did in response.
     * Use this to correlate UI events with PTP/USB communication.
     */
    fun action(msg: String) = emit("ACTION", msg)

    // ── Helpers ───────────────────────────────────────────────

    /** Log with explicit error level. Always logged regardless of focus mode. */
    fun err(tag: String, msg: String, t: Throwable? = null) {
        if (!enabled) return
        val full = "[$tag] $msg"
        FileLogger.error(full, t)
    }

    // ── Timing ────────────────────────────────────────────────

    fun timeStart(key: String) {
        if (!enabled) return
        timers[key] = System.nanoTime()
    }

    fun timeEnd(tag: String, key: String, msg: String = "") {
        if (!enabled) return
        val start = timers.remove(key) ?: run {
            emit(tag, "$msg (timer '$key' not found)")
            return
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        emit(tag, "$msg [${elapsedMs}ms]")
    }

    // ── Scope tracking ────────────────────────────────────────

    fun scope(tag: String, name: String): Scope {
        emit(tag, "→ $name")
        return Scope(tag, name, System.nanoTime())
    }

    class Scope(private val tag: String, private val name: String, private val startNano: Long) {
        fun end(result: String = "OK") {
            if (!enabled) return
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000
            emit(tag, "← $name $result [${elapsedMs}ms]")
        }

        fun fail(error: String) = end("FAIL: $error")
        fun fail(t: Throwable) = end("FAIL: ${t::class.simpleName}: ${t.message}")
    }

    // ── Separator / Marker ────────────────────────────────────

    fun marker(label: String) {
        if (!enabled) return
        FileLogger.debug("════════════════ $label ════════════════")
    }

    // ── Internal ──────────────────────────────────────────────

    @PublishedApi
    internal fun emit(tag: String, msg: String) {
        if (!enabled) return
        if (tag in disabledCategories) return
        if (usbTetherFocus && tag !in tetherCategories) return
        FileLogger.debug("[$tag] $msg")
    }
}
