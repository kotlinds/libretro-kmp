package dev.kotlinds.libretrokmp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PixelConverterTest {

    @Test
    fun convertsXrgb8888AndForcesOpaqueAlpha() {
        // One row of 2 pixels, little-endian 0x00112233 and 0x00FFFFFF, with 4 bytes of row padding.
        val data = byteArrayOf(0x33, 0x22, 0x11, 0x00, -1, -1, -1, 0x00, 0, 0, 0, 0)
        val frame = PixelConverter.convert(PixelFormat.XRGB8888, width = 2, height = 1, pitch = 12, data = data)
        assertContentEquals(intArrayOf(0xFF112233.toInt(), 0xFFFFFFFF.toInt()), frame.pixels)
    }

    @Test
    fun expandsRgb565ToFullRange() {
        assertEquals(0xFFFFFFFF.toInt(), PixelConverter.rgb565ToArgb(0xFFFF))
        assertEquals(0xFF000000.toInt(), PixelConverter.rgb565ToArgb(0x0000))
        assertEquals(0xFFFF0000.toInt(), PixelConverter.rgb565ToArgb(0xF800))
        assertEquals(0xFF00FF00.toInt(), PixelConverter.rgb565ToArgb(0x07E0))
    }

    @Test
    fun expandsRgb1555ToFullRange() {
        assertEquals(0xFFFFFFFF.toInt(), PixelConverter.rgb1555ToArgb(0x7FFF))
        assertEquals(0xFF0000FF.toInt(), PixelConverter.rgb1555ToArgb(0x001F))
    }

    @Test
    fun respectsPitchBetweenRows() {
        // 1x2 frame in RGB565 with a pitch of 4 bytes (2 bytes of padding per row).
        val data = byteArrayOf(0x00, 0xF8.toByte(), 0, 0, 0x1F, 0x00, 0, 0)
        val frame = PixelConverter.convert(PixelFormat.RGB565, width = 1, height = 2, pitch = 4, data = data)
        assertContentEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), frame.pixels)
    }
}
