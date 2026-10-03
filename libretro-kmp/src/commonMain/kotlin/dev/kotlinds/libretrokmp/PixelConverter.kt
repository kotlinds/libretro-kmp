package dev.kotlinds.libretrokmp

/**
 * Converts the raw frame buffers given by cores (in their [PixelFormat]) to opaque ARGB pixels.
 *
 * Platforms copy the core's buffer (`height` rows of `pitch` bytes) into a [ByteArray] and call
 * [convert]; the conversion itself is shared. Native endianness is little-endian on every target
 * this library supports.
 *
 * The numbers below are the bit layouts of the pixel formats (shifts and masks), hence the
 * suppressed MagicNumber rule.
 */
@Suppress("MagicNumber")
internal object PixelConverter {

    private const val OPAQUE = 0xFF000000.toInt()

    fun convert(format: PixelFormat, width: Int, height: Int, pitch: Int, data: ByteArray): VideoFrame {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * pitch
            val out = y * width
            when (format) {
                PixelFormat.XRGB8888 -> for (x in 0 until width) {
                    pixels[out + x] = OPAQUE or (int32(data, row + x * 4) and 0x00FFFFFF)
                }

                PixelFormat.RGB565 -> for (x in 0 until width) {
                    pixels[out + x] = rgb565ToArgb(int16(data, row + x * 2))
                }

                PixelFormat.RGB1555 -> for (x in 0 until width) {
                    pixels[out + x] = rgb1555ToArgb(int16(data, row + x * 2))
                }
            }
        }
        return VideoFrame(width, height, pixels)
    }

    fun bytesPerPixel(format: PixelFormat): Int = if (format == PixelFormat.XRGB8888) 4 else 2

    fun rgb565ToArgb(value: Int): Int {
        val r = (value shr 11) and 0x1F
        val g = (value shr 5) and 0x3F
        val b = value and 0x1F
        return OPAQUE or (expand5(r) shl 16) or (expand6(g) shl 8) or expand5(b)
    }

    fun rgb1555ToArgb(value: Int): Int {
        val r = (value shr 10) and 0x1F
        val g = (value shr 5) and 0x1F
        val b = value and 0x1F
        return OPAQUE or (expand5(r) shl 16) or (expand5(g) shl 8) or expand5(b)
    }

    /** 5-bit channel to 8 bits, replicating the high bits so 0x1F becomes 0xFF. */
    private fun expand5(value: Int) = (value shl 3) or (value shr 2)

    private fun expand6(value: Int) = (value shl 2) or (value shr 4)

    private fun int16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun int32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
