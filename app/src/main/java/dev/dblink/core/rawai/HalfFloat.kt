package dev.dblink.core.rawai

/** IEEE-754 binary16 conversion (not bfloat16). */
object HalfFloat {
    fun fromFloat(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        var exponent = (bits ushr 23) and 0xff
        var mantissa = bits and 0x7fffff
        if (exponent == 0xff) {
            return (sign or 0x7c00 or if (mantissa == 0) 0 else ((mantissa ushr 13) or 1)).toShort()
        }
        exponent = exponent - 127 + 15
        if (exponent >= 0x1f) return (sign or 0x7c00).toShort()
        if (exponent <= 0) {
            if (exponent < -10) return sign.toShort()
            mantissa = mantissa or 0x800000
            val shift = 14 - exponent
            var halfMantissa = mantissa ushr shift
            val remainder = mantissa and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (remainder > halfway || (remainder == halfway && (halfMantissa and 1) != 0)) halfMantissa++
            return (sign or halfMantissa).toShort()
        }
        var half = sign or (exponent shl 10) or (mantissa ushr 13)
        val remainder = mantissa and 0x1fff
        if (remainder > 0x1000 || (remainder == 0x1000 && (half and 1) != 0)) half++
        return half.toShort()
    }

    fun toFloat(value: Short): Float {
        val half = value.toInt() and 0xffff
        val sign = (half and 0x8000) shl 16
        val exponent = (half ushr 10) and 0x1f
        val mantissa = half and 0x3ff
        val bits = when (exponent) {
            0 -> {
                if (mantissa == 0) sign else {
                    var normalized = mantissa
                    var shift = 0
                    while ((normalized and 0x400) == 0) {
                        normalized = normalized shl 1
                        shift++
                    }
                    normalized = normalized and 0x3ff
                    sign or ((127 - 14 - shift) shl 23) or (normalized shl 13)
                }
            }
            0x1f -> sign or 0x7f800000 or (mantissa shl 13)
            else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
        }
        return Float.fromBits(bits)
    }
}
