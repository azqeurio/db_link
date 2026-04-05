package dev.pl36.cameralink.core.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed PTP DevicePropDesc dataset.
 *
 * Describes a device property: its current value, default, and allowed values.
 */
data class PtpPropertyDesc(
    val propertyCode: Int,
    val dataType: Int,
    val getSet: Int,
    val factoryDefault: Long,
    val currentValue: Long,
    val formFlag: Int,
    /** For enumeration form (formFlag=2): list of allowed values. */
    val enumValues: List<Long> = emptyList(),
    /** For range form (formFlag=1): min, max, step. */
    val rangeMin: Long = 0,
    val rangeMax: Long = 0,
    val rangeStep: Long = 0,
) {
    val isReadOnly: Boolean get() = getSet == 0
    val isReadWrite: Boolean get() = getSet == 1
    val isEnumeration: Boolean get() = formFlag == 2
    val isRange: Boolean get() = formFlag == 1

    companion object {
        // PTP data type codes
        const val TYPE_INT8 = 0x0001
        const val TYPE_UINT8 = 0x0002
        const val TYPE_INT16 = 0x0003
        const val TYPE_UINT16 = 0x0004
        const val TYPE_INT32 = 0x0005
        const val TYPE_UINT32 = 0x0006
        const val TYPE_INT64 = 0x0007
        const val TYPE_UINT64 = 0x0008
        const val TYPE_STR = 0xFFFF

        fun parse(data: ByteArray): PtpPropertyDesc {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val propertyCode = buf.getShort().toInt() and 0xFFFF
            val dataType = buf.getShort().toInt() and 0xFFFF
            val getSet = buf.get().toInt() and 0xFF

            val typeSize = dataTypeSize(dataType)
            val factoryDefault = readValue(buf, dataType, typeSize)
            val currentValue = readValue(buf, dataType, typeSize)

            val formFlag = buf.get().toInt() and 0xFF

            var enumValues = emptyList<Long>()
            var rangeMin = 0L
            var rangeMax = 0L
            var rangeStep = 0L

            when (formFlag) {
                1 -> { // Range
                    rangeMin = readValue(buf, dataType, typeSize)
                    rangeMax = readValue(buf, dataType, typeSize)
                    rangeStep = readValue(buf, dataType, typeSize)
                }
                2 -> { // Enumeration
                    val count = if (buf.remaining() >= 2) buf.getShort().toInt() and 0xFFFF else 0
                    enumValues = (0 until count).mapNotNull {
                        if (buf.remaining() >= typeSize) readValue(buf, dataType, typeSize) else null
                    }
                }
            }

            return PtpPropertyDesc(
                propertyCode = propertyCode,
                dataType = dataType,
                getSet = getSet,
                factoryDefault = factoryDefault,
                currentValue = currentValue,
                formFlag = formFlag,
                enumValues = enumValues,
                rangeMin = rangeMin,
                rangeMax = rangeMax,
                rangeStep = rangeStep,
            )
        }

        private fun dataTypeSize(type: Int): Int = when (type) {
            TYPE_INT8, TYPE_UINT8 -> 1
            TYPE_INT16, TYPE_UINT16 -> 2
            TYPE_INT32, TYPE_UINT32 -> 4
            TYPE_INT64, TYPE_UINT64 -> 8
            else -> 2 // default to 16-bit
        }

        private fun readValue(buf: ByteBuffer, type: Int, size: Int): Long {
            if (buf.remaining() < size) return 0
            return when (type) {
                TYPE_INT8 -> buf.get().toLong()
                TYPE_UINT8 -> (buf.get().toInt() and 0xFF).toLong()
                TYPE_INT16 -> buf.getShort().toLong()
                TYPE_UINT16 -> (buf.getShort().toInt() and 0xFFFF).toLong()
                TYPE_INT32 -> buf.getInt().toLong()
                TYPE_UINT32 -> (buf.getInt().toLong() and 0xFFFFFFFFL)
                TYPE_INT64 -> buf.getLong()
                TYPE_UINT64 -> buf.getLong() // Can't fully represent unsigned in Long
                else -> {
                    if (buf.remaining() >= 2) (buf.getShort().toInt() and 0xFFFF).toLong() else 0
                }
            }
        }
    }
}
