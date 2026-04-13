package dev.dblink.core.protocol

import dev.dblink.core.logging.D
import dev.dblink.core.model.ConnectMode

object ConnectModeParser {
    /**
     * Parses the response from /get_connectmode.cgi.
     * Camera typically returns XML like: <?xml version="1.0"?><connectmode>OPC</connectmode>
     * OPC = record/remote mode, standalone = play mode
     */
    fun parse(raw: String): ConnectMode {
        D.proto("Parsing connect mode, raw='${raw.take(100)}'")
        val normalized = raw.trim().lowercase()
        return when {
            "maintenance" in normalized -> ConnectMode.Maintenance
            "shutter" in normalized -> ConnectMode.Shutter
            "opc" in normalized -> ConnectMode.Record
            "record" in normalized || ">rec<" in normalized || "mode=rec" in normalized -> ConnectMode.Record
            "standalone" in normalized -> ConnectMode.Play
            // Observed connect-mode variants: "playmodeonly_private" stays in
            // playback, while "private" allows remote-control access.
            "playmodeonly_private" in normalized -> ConnectMode.Play
            "private" in normalized -> ConnectMode.Record
            "play" in normalized -> ConnectMode.Play
            else -> ConnectMode.Unknown
        }.also { D.proto("Connect mode resolved: $it") }
    }
}
