package dev.pl36.cameralink.core.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Parsed PTP DeviceInfo dataset.
 *
 * Contains camera identification, capabilities, and supported operations.
 */
data class PtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Int,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operationsSupported: Set<Int>,
    val eventsSupported: Set<Int>,
    val devicePropertiesSupported: Set<Int>,
    val captureFormats: Set<Int>,
    val imageFormats: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
) {
    /** Firmware version extracted from device version string. */
    val firmwareVersion: String get() = deviceVersion.ifBlank { "Unknown" }

    /** Check if this is an Olympus or OM System camera. */
    fun isOlympusOrOmSystem(): Boolean {
        val mfg = manufacturer.uppercase()
        val mdl = model.uppercase()
        return mfg.contains("OLYMPUS") || mfg.contains("OMSYSTEM") ||
            mfg.contains("OM DIGITAL") ||
            mdl.startsWith("E-M") || mdl.startsWith("OM-")
    }

    /** Check if an OM-D style operation is supported. */
    fun supportsOmdCapture(): Boolean = PtpConstants.OmdOp.Capture in operationsSupported
    fun supportsOmdLiveView(): Boolean = PtpConstants.OmdOp.GetLiveViewImage in operationsSupported
    fun supportsOmdSetProperties(): Boolean = PtpConstants.OmdOp.SetProperties in operationsSupported
    fun supportsOmdGetImage(): Boolean = PtpConstants.OmdOp.GetImage in operationsSupported
    fun supportsGetRunMode(): Boolean = PtpConstants.OlympusOp.GetCameraControlMode in operationsSupported
    fun supportsChangeRunMode(): Boolean = PtpConstants.OlympusOp.SetCameraControlMode in operationsSupported

    companion object {
        /**
         * Parse DeviceInfo from raw PTP data bytes.
         *
         * DeviceInfo dataset format:
         *   uint16  StandardVersion
         *   uint32  VendorExtensionID
         *   uint16  VendorExtensionVersion
         *   string  VendorExtensionDesc
         *   uint16  FunctionalMode
         *   uint16[] OperationsSupported
         *   uint16[] EventsSupported
         *   uint16[] DevicePropertiesSupported
         *   uint16[] CaptureFormats
         *   uint16[] ImageFormats
         *   string  Manufacturer
         *   string  Model
         *   string  DeviceVersion
         *   string  SerialNumber
         */
        fun parse(data: ByteArray): PtpDeviceInfo {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val standardVersion = buf.getShort().toInt() and 0xFFFF
            val vendorExtensionId = buf.getInt()
            val vendorExtensionVersion = buf.getShort().toInt() and 0xFFFF
            val vendorExtensionDesc = readPtpString(buf)
            val functionalMode = buf.getShort().toInt() and 0xFFFF
            val operationsSupported = readUint16Array(buf).toSet()
            val eventsSupported = readUint16Array(buf).toSet()
            val devicePropertiesSupported = readUint16Array(buf).toSet()
            val captureFormats = readUint16Array(buf).toSet()
            val imageFormats = readUint16Array(buf).toSet()
            val manufacturer = readPtpString(buf)
            val model = readPtpString(buf)
            val deviceVersion = readPtpString(buf)
            val serialNumber = readPtpString(buf)

            return PtpDeviceInfo(
                standardVersion = standardVersion,
                vendorExtensionId = vendorExtensionId,
                vendorExtensionVersion = vendorExtensionVersion,
                vendorExtensionDesc = vendorExtensionDesc,
                functionalMode = functionalMode,
                operationsSupported = operationsSupported,
                eventsSupported = eventsSupported,
                devicePropertiesSupported = devicePropertiesSupported,
                captureFormats = captureFormats,
                imageFormats = imageFormats,
                manufacturer = manufacturer,
                model = model,
                deviceVersion = deviceVersion,
                serialNumber = serialNumber,
            )
        }

        /**
         * Read a PTP string (1-byte length prefix, then UCS-2LE chars, null-terminated).
         */
        private fun readPtpString(buf: ByteBuffer): String {
            if (buf.remaining() < 1) return ""
            val numChars = buf.get().toInt() and 0xFF
            if (numChars == 0) return ""

            val bytes = ByteArray(numChars * 2)
            if (buf.remaining() >= bytes.size) {
                buf.get(bytes)
            }
            // Decode UCS-2LE, strip trailing null
            return String(bytes, Charset.forName("UTF-16LE")).trimEnd('\u0000')
        }

        /**
         * Read a PTP uint16 array (uint32 count, then uint16 values).
         */
        private fun readUint16Array(buf: ByteBuffer): List<Int> {
            if (buf.remaining() < 4) return emptyList()
            val count = buf.getInt()
            return (0 until count).mapNotNull {
                if (buf.remaining() >= 2) buf.getShort().toInt() and 0xFFFF else null
            }
        }
    }
}
