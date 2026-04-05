package dev.pl36.cameralink.core.protocol

import android.util.Xml
import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.CameraCommandDefinition
import dev.pl36.cameralink.core.model.CameraCommandList
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

class CameraCommandListParser {
    fun parse(xml: String): CameraCommandList {
        D.proto("Parsing command list XML, length=${xml.length}")
        require(xml.isNotBlank()) { "Command list XML is empty." }

        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var version = "unknown"
        val commands = mutableListOf<CameraCommandDefinition>()

        var commandName: String? = null
        var supported = true
        var param1: String? = null
        var param2: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "version" -> version = safeNextText(parser)
                        "cgi" -> {
                            commandName = parser.getAttributeValue(null, "name")
                            supported = true
                            param1 = null
                            param2 = null
                        }
                        "support" -> {
                            supported = safeNextText(parser)
                                .lowercase() !in setOf("0", "false", "no", "unsupported")
                        }
                        "param1" -> param1 = safeNextText(parser).ifBlank { null }
                        "param2" -> param2 = safeNextText(parser).ifBlank { null }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "cgi" && commandName != null) {
                        commands += CameraCommandDefinition(
                            name = commandName,
                            supported = supported,
                            param1 = param1,
                            param2 = param2,
                        )
                        commandName = null
                    }
                }
            }
            eventType = parser.next()
        }

        require(commands.isNotEmpty()) { "No CGI entries were found in the command list." }

        D.proto("Parsed ${commands.size} commands, version=$version, supported=${commands.count { it.supported }}")
        return CameraCommandList(version = version, commands = commands)
    }

    private fun safeNextText(parser: XmlPullParser): String {
        var text = ""
        var depth = 1
        var type = parser.next()
        while (depth > 0 && type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> text += parser.text
            }
            if (depth > 0) {
                type = parser.next()
            }
        }
        return text.trim()
    }
}
