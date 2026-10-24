package eu.studio742.imago.core.raw

/** IEEE binary16 storage with binary32 arithmetic, matching the GPU's RGBA16F working targets. */
class HalfFloats(val values: ShortArray) {
    val size: Int get() = values.size
    val indices: IntRange get() = values.indices
    operator fun get(index: Int): Float = decodeHalf(values[index])
    operator fun set(index: Int, value: Float) { values[index] = encodeHalf(value) }
    fun copyOf() = HalfFloats(values.copyOf())
    fun toFloatArray() = FloatArray(size) { get(it) }
    fun all(predicate: (Float) -> Boolean): Boolean = indices.all { predicate(get(it)) }
    fun copyInto(target: FloatArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size) {
        for (i in startIndex until endIndex) target[destinationOffset + i - startIndex] = get(i)
    }
}

fun decodeHalf(value: Short): Float {
    val bits = value.toInt() and 65535
    val sign = (bits and 32768) shl 16
    val exponent = bits ushr 10 and 31
    val mantissa = bits and 1023
    return if (exponent == 0) {
        if (mantissa == 0) Float.fromBits(sign) else (if (sign == 0) 1f else -1f) * mantissa * (1f / 16777216f)
    } else Float.fromBits(sign or ((if (exponent == 31) 255 else exponent + 112) shl 23) or (mantissa shl 13))
}

fun encodeHalf(value: Float): Short {
    val bits = value.toRawBits()
    val sign = bits ushr 16 and 32768
    val exponent = (bits ushr 23 and 255) - 127
    val mantissa = bits and 8388607
    if (value.isNaN()) return 0
    if (exponent > 15) return (sign or 31743).toShort()
    if (exponent < -25) return sign.toShort()
    val shift = if (exponent < -14) -exponent - 1 else 13
    val significand = if (exponent < -14) mantissa or 8388608 else mantissa
    var rounded = significand ushr shift
    val remainder = significand and ((1 shl shift) - 1)
    val halfway = 1 shl (shift - 1)
    if (remainder > halfway || (remainder == halfway && rounded and 1 != 0)) rounded++
    val magnitude = if (exponent < -14) rounded else ((exponent + 15) shl 10) + rounded
    return (sign or magnitude.coerceAtMost(31743)).toShort()
}
