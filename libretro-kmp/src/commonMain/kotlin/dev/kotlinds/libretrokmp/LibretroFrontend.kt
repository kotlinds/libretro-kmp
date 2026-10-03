package dev.kotlinds.libretrokmp

/**
 * What the frontend (your application) provides to a [LibretroCore].
 *
 * Every callback is invoked on the thread calling [LibretroCore.run] (or [LibretroCore.loadGame]
 * for the environment queries made at load time). Only [systemDirectory] and [saveDirectory] are
 * required; everything else has a sensible default.
 */
interface LibretroFrontend {

    /** Directory where cores look for BIOS and system files. */
    val systemDirectory: String

    /** Directory where cores write battery saves (and other persistent data). */
    val saveDirectory: String

    /**
     * Value of a core option (libretro "variable"), e.g. `"melonds_boot_directly"` → `"enabled"`,
     * or null to let the core use its default.
     */
    fun variable(key: String): String? = null

    /** A frame is ready. Not called for duplicated frames (the core repeats the previous one). */
    fun onVideoFrame(frame: VideoFrame) {}

    /**
     * Audio samples are ready: [samples] holds [frames] stereo frames, interleaved (L, R, L, R…).
     * The array may be reused by the next call: copy it if you keep it.
     */
    fun onAudio(samples: ShortArray, frames: Int) {}

    /** The core is about to query inputs for the current frame. */
    fun onInputPoll() {}

    /**
     * State of one input. For [Device.JOYPAD], [id] is a [JoypadButton] id and the result is 1 when
     * pressed; for [Device.POINTER], see [PointerId] (coordinates span -0x7FFF..0x7FFF).
     */
    fun inputState(port: Int, device: Int, index: Int, id: Int): Short = 0

    /** A log line from the core. */
    fun onLog(level: LogLevel, message: String) {}

    /** Language reported to the core (RETRO_LANGUAGE_*), English by default. */
    val language: Int get() = 0
}
