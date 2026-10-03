package dev.kotlinds.libretrokmp

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.Structure

/*
 * JNA mapping of the libretro C API (libs/include/libretro.h), used by the JVM implementation.
 *
 * Only the parts of the API this library needs are mapped. Names intentionally mirror the C names
 * so that the libretro documentation can be followed directly. Everything here is internal: use
 * [LibretroCore] instead.
 */

/** Functions exported by every libretro core. */
@Suppress("FunctionName")
internal interface LibretroLib : Library {
    fun retro_api_version(): Int

    fun retro_set_environment(callback: EnvironmentCallback)
    fun retro_set_video_refresh(callback: VideoRefreshCallback)
    fun retro_set_audio_sample(callback: AudioSampleCallback)
    fun retro_set_audio_sample_batch(callback: AudioSampleBatchCallback)
    fun retro_set_input_poll(callback: InputPollCallback)
    fun retro_set_input_state(callback: InputStateCallback)

    fun retro_init()
    fun retro_deinit()

    fun retro_get_system_info(info: RetroSystemInfo)
    fun retro_get_system_av_info(info: RetroSystemAvInfo)
    fun retro_set_controller_port_device(port: Int, device: Int)

    fun retro_load_game(game: RetroGameInfo): Boolean
    fun retro_unload_game()
    fun retro_reset()
    fun retro_run()

    fun retro_serialize_size(): Long
    fun retro_serialize(data: ByteArray, size: Long): Boolean
    fun retro_unserialize(data: ByteArray, size: Long): Boolean

    fun retro_get_memory_data(id: Int): Pointer?
    fun retro_get_memory_size(id: Int): Long
}

// region Callbacks (C function pointers the core calls back into)

/** `bool (*)(unsigned cmd, void *data)`: the core asks the frontend for things (directories, options...). */
internal fun interface EnvironmentCallback : Callback {
    fun invoke(cmd: Int, data: Pointer?): Byte
}

/** `void (*)(const void *data, unsigned width, unsigned height, size_t pitch)`: a frame is ready. */
internal fun interface VideoRefreshCallback : Callback {
    fun invoke(data: Pointer?, width: Int, height: Int, pitch: Long)
}

/** `void (*)(int16_t left, int16_t right)`: one stereo audio sample. */
internal fun interface AudioSampleCallback : Callback {
    fun invoke(left: Short, right: Short)
}

/** `size_t (*)(const int16_t *data, size_t frames)`: a batch of interleaved stereo samples. */
internal fun interface AudioSampleBatchCallback : Callback {
    fun invoke(data: Pointer, frames: Long): Long
}

/** `void (*)(void)`: the core is about to read input for this frame. */
internal fun interface InputPollCallback : Callback {
    fun invoke()
}

/** `int16_t (*)(unsigned port, unsigned device, unsigned index, unsigned id)`: state of one input. */
internal fun interface InputStateCallback : Callback {
    fun invoke(port: Int, device: Int, index: Int, id: Int): Short
}

/** `void (*)(enum retro_log_level level, const char *fmt, ...)`: variadic args are ignored. */
internal fun interface LogCallback : Callback {
    fun invoke(level: Int, format: String?)
}

// endregion

// region Structures

@Structure.FieldOrder("library_name", "library_version", "valid_extensions", "need_fullpath", "block_extract")
internal class RetroSystemInfo : Structure() {
    @JvmField var library_name: String? = null
    @JvmField var library_version: String? = null
    @JvmField var valid_extensions: String? = null
    @JvmField var need_fullpath: Boolean = false
    @JvmField var block_extract: Boolean = false
}

@Structure.FieldOrder("base_width", "base_height", "max_width", "max_height", "aspect_ratio")
internal open class RetroGameGeometry : Structure {
    constructor() : super()
    constructor(pointer: Pointer) : super(pointer) { read() }

    @JvmField var base_width: Int = 0
    @JvmField var base_height: Int = 0
    @JvmField var max_width: Int = 0
    @JvmField var max_height: Int = 0
    @JvmField var aspect_ratio: Float = 0f

    class ByValue : RetroGameGeometry(), Structure.ByValue
}

@Structure.FieldOrder("fps", "sample_rate")
internal open class RetroSystemTiming : Structure() {
    @JvmField var fps: Double = 0.0
    @JvmField var sample_rate: Double = 0.0

    class ByValue : RetroSystemTiming(), Structure.ByValue
}

@Structure.FieldOrder("geometry", "timing")
internal class RetroSystemAvInfo : Structure {
    constructor() : super()
    constructor(pointer: Pointer) : super(pointer) { read() }

    @JvmField var geometry: RetroGameGeometry.ByValue = RetroGameGeometry.ByValue()
    @JvmField var timing: RetroSystemTiming.ByValue = RetroSystemTiming.ByValue()
}

@Structure.FieldOrder("path", "data", "size", "meta")
internal class RetroGameInfo : Structure() {
    @JvmField var path: String? = null
    @JvmField var data: Pointer? = null
    @JvmField var size: Long = 0
    @JvmField var meta: String? = null
}

/** `struct retro_variable { const char *key; const char *value; }` used by GET_VARIABLE. */
@Structure.FieldOrder("key", "value")
internal class RetroVariable(pointer: Pointer) : Structure(pointer) {
    @JvmField var key: String? = null
    @JvmField var value: Pointer? = null

    init {
        read()
    }
}

/** `struct retro_log_callback { retro_log_printf_t log; }` used by GET_LOG_INTERFACE. */
@Structure.FieldOrder("log")
internal class RetroLogCallback(pointer: Pointer) : Structure(pointer) {
    @JvmField var log: LogCallback? = null
}

// endregion
