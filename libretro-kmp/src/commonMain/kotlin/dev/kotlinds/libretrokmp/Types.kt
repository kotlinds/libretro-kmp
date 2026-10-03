package dev.kotlinds.libretrokmp

/**
 * Information reported by a core (`struct retro_system_info`).
 *
 * @param libraryName Name of the core, e.g. "melonDS".
 * @param libraryVersion Version of the core.
 * @param validExtensions Supported file extensions, separated by `|` (e.g. "nds|dsi").
 * @param needFullPath True when the core loads the game from its path itself; false when the
 *   frontend must pass the file contents in memory (handled by [LibretroCore.loadGame]).
 * @param blockExtract True when the frontend must not extract archives before loading.
 */
data class SystemInfo(
    val libraryName: String,
    val libraryVersion: String,
    val validExtensions: String,
    val needFullPath: Boolean,
    val blockExtract: Boolean,
)

/** Video geometry and timings (`struct retro_system_av_info`). */
data class SystemAvInfo(
    val geometry: GameGeometry,
    val timing: SystemTiming,
)

/**
 * Video geometry (`struct retro_game_geometry`). Frames may be smaller than the maximum size; the
 * actual size of each frame is given by [VideoFrame].
 */
data class GameGeometry(
    val baseWidth: Int,
    val baseHeight: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    /** Display aspect ratio, or 0 to use baseWidth / baseHeight. */
    val aspectRatio: Float,
)

/** Timings (`struct retro_system_timing`): frames and audio samples per second. */
data class SystemTiming(
    val fps: Double,
    val sampleRate: Double,
)

/**
 * A decoded video frame: [pixels] are ARGB (0xAARRGGBB, always opaque), row-major, [width] ×
 * [height]. Whatever the core's pixel format, frames are converted to this single format.
 */
class VideoFrame(val width: Int, val height: Int, val pixels: IntArray)

/** Memory regions a core may expose (RETRO_MEMORY_*, ids from libretro.h). */
@Suppress("MagicNumber")
enum class MemoryType(val id: Int) {
    /** Battery-backed save memory (in-game saves). */
    SAVE_RAM(0),

    /** Real-time clock data. */
    RTC(1),

    /** The console's main RAM. */
    SYSTEM_RAM(2),

    /** Video RAM. */
    VIDEO_RAM(3),
}

/** Pixel formats a core can request (RETRO_PIXEL_FORMAT_*). */
enum class PixelFormat(val id: Int) {
    /** 15-bit 0RGB1555, native endian (libretro's default). */
    RGB1555(0),

    /** 32-bit XRGB8888, native endian. */
    XRGB8888(1),

    /** 16-bit RGB565, native endian. */
    RGB565(2),
    ;

    companion object {
        fun fromId(id: Int): PixelFormat? = entries.firstOrNull { it.id == id }
    }
}

/** Log levels (RETRO_LOG_*). */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    companion object {
        fun fromId(id: Int): LogLevel = entries.getOrElse(id) { ERROR }
    }
}

/** Input device types (RETRO_DEVICE_*). */
object Device {
    const val NONE = 0
    const val JOYPAD = 1
    const val MOUSE = 2
    const val KEYBOARD = 3
    const val LIGHTGUN = 4
    const val ANALOG = 5
    const val POINTER = 6
}

/** Joypad button ids (RETRO_DEVICE_ID_JOYPAD_*), RetroPad layout. */
object JoypadButton {
    const val B = 0
    const val Y = 1
    const val SELECT = 2
    const val START = 3
    const val UP = 4
    const val DOWN = 5
    const val LEFT = 6
    const val RIGHT = 7
    const val A = 8
    const val X = 9
    const val L = 10
    const val R = 11
    const val L2 = 12
    const val R2 = 13
    const val L3 = 14
    const val R3 = 15
}

/** Pointer (touch screen) ids (RETRO_DEVICE_ID_POINTER_*). */
object PointerId {
    /** X coordinate, from -0x7FFF (left of the frame) to 0x7FFF (right). */
    const val X = 0

    /** Y coordinate, from -0x7FFF (top of the frame) to 0x7FFF (bottom). */
    const val Y = 1

    /** 1 while touching. */
    const val PRESSED = 2
}
