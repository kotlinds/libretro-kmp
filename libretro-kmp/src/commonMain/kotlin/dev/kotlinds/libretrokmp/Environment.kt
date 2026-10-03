package dev.kotlinds.libretrokmp

/**
 * Access to the `void *data` argument of a libretro environment call, implemented per platform
 * (JNA pointer on the JVM, cinterop pointer on native targets, JNI on Android).
 *
 * Each method reads or writes `data` as the type the given command expects.
 */
internal interface EnvironmentData {
    /** `*(int *)data` / `*(unsigned *)data` / `*(enum *)data`. */
    fun readInt(): Int

    fun writeInt(value: Int)

    /** `*(bool *)data` (one byte). */
    fun writeBool(value: Boolean)

    /** `*(const char **)data = value` (the string must outlive the call: platforms keep it alive). */
    fun writeString(value: String)

    /** `((struct retro_variable *)data)->key`. */
    fun readVariableKey(): String?

    /** `((struct retro_variable *)data)->value = value` (kept alive like [writeString]). */
    fun writeVariableValue(value: String)

    /** `((struct retro_log_callback *)data)->log = <the platform's log function>`. */
    fun writeLogCallback()

    /** `*(const struct retro_system_av_info *)data`. */
    fun readAvInfo(): SystemAvInfo

    /** `*(const struct retro_game_geometry *)data`. */
    fun readGeometry(): GameGeometry
}

/**
 * Answers the core's environment calls (`retro_environment_t`): the part of the frontend protocol
 * where the core asks for directories, options, pixel format support, etc.
 *
 * Shared by every platform: platforms only translate the raw `data` pointer ([EnvironmentData]).
 * Unsupported commands return false, which every core must handle.
 */
internal class Environment(private val frontend: LibretroFrontend) {

    /** Pixel format requested by the core (libretro's default is 0RGB1555). */
    var pixelFormat: PixelFormat = PixelFormat.RGB1555
        private set

    /** Last AV info pushed by the core (SET_SYSTEM_AV_INFO), if any. */
    var avInfo: SystemAvInfo? = null
        private set

    /** Last geometry pushed by the core (SET_GEOMETRY), if any. */
    var geometry: GameGeometry? = null
        private set

    /** One small handler per supported command; a command without handler is unsupported. */
    private val handlers: Map<Int, (EnvironmentData?) -> Boolean> = buildMap {
        // Notifications we accept without needing to act on them.
        listOf(
            EnvironmentCommand.SET_PERFORMANCE_LEVEL,
            EnvironmentCommand.SET_INPUT_DESCRIPTORS,
            EnvironmentCommand.SET_CONTROLLER_INFO,
            EnvironmentCommand.SET_MEMORY_MAPS,
            EnvironmentCommand.SET_SERIALIZATION_QUIRKS,
            EnvironmentCommand.SET_CORE_OPTIONS_DISPLAY,
            EnvironmentCommand.SET_SUPPORT_NO_GAME,
            EnvironmentCommand.SET_VARIABLES,
        ).forEach { put(it) { true } }

        put(EnvironmentCommand.GET_CAN_DUPE) { data -> data.answer { writeBool(true) } }
        put(EnvironmentCommand.GET_SYSTEM_DIRECTORY) { data -> data.answer { writeString(frontend.systemDirectory) } }
        put(EnvironmentCommand.GET_SAVE_DIRECTORY) { data -> data.answer { writeString(frontend.saveDirectory) } }
        put(EnvironmentCommand.SET_PIXEL_FORMAT, ::setPixelFormat)
        put(EnvironmentCommand.GET_VARIABLE, ::getVariable)
        put(EnvironmentCommand.GET_VARIABLE_UPDATE) { data -> data.answer { writeBool(false) } }
        put(EnvironmentCommand.GET_LOG_INTERFACE) { data -> data.answer { writeLogCallback() } }
        put(EnvironmentCommand.SET_SYSTEM_AV_INFO) { data -> data.answer { avInfo = readAvInfo() } }
        put(EnvironmentCommand.SET_GEOMETRY) { data -> data.answer { geometry = readGeometry() } }
        put(EnvironmentCommand.GET_LANGUAGE) { data -> data.answer { writeInt(frontend.language) } }
        put(EnvironmentCommand.GET_AUDIO_VIDEO_ENABLE) { data -> data.answer { writeInt(AUDIO_AND_VIDEO_ENABLED) } }
        // Legacy options (version 0): the core then asks values one by one with GET_VARIABLE.
        put(EnvironmentCommand.GET_CORE_OPTIONS_VERSION) { data -> data.answer { writeInt(0) } }
    }

    /** Handles one environment call; false means "unsupported" (or missing data). */
    fun handle(command: Int, data: EnvironmentData?): Boolean =
        handlers[command and EnvironmentCommand.EXPERIMENTAL.inv()]?.invoke(data) ?: false

    private fun setPixelFormat(data: EnvironmentData?): Boolean {
        val format = data?.readInt()?.let(PixelFormat::fromId)
        if (format != null) pixelFormat = format
        return format != null
    }

    private fun getVariable(data: EnvironmentData?): Boolean {
        val value = data?.readVariableKey()?.let(frontend::variable)
        if (value != null) data.writeVariableValue(value)
        return value != null
    }

    private inline fun EnvironmentData?.answer(block: EnvironmentData.() -> Unit): Boolean {
        this?.block()
        return this != null
    }

    private companion object {
        /** GET_AUDIO_VIDEO_ENABLE bits: 1 = video, 2 = audio. */
        const val AUDIO_AND_VIDEO_ENABLED = 0b11
    }
}

/** Environment command ids (RETRO_ENVIRONMENT_*) handled by [Environment]. */
internal object EnvironmentCommand {
    const val EXPERIMENTAL = 0x10000
    const val GET_CAN_DUPE = 3
    const val SET_PERFORMANCE_LEVEL = 8
    const val GET_SYSTEM_DIRECTORY = 9
    const val SET_PIXEL_FORMAT = 10
    const val SET_INPUT_DESCRIPTORS = 11
    const val GET_VARIABLE = 15
    const val SET_VARIABLES = 16
    const val GET_VARIABLE_UPDATE = 17
    const val SET_SUPPORT_NO_GAME = 18
    const val GET_LOG_INTERFACE = 27
    const val GET_SAVE_DIRECTORY = 31
    const val SET_SYSTEM_AV_INFO = 32
    const val SET_CONTROLLER_INFO = 35
    const val SET_MEMORY_MAPS = 36
    const val SET_GEOMETRY = 37
    const val GET_LANGUAGE = 39
    const val SET_SERIALIZATION_QUIRKS = 44
    const val GET_AUDIO_VIDEO_ENABLE = 47
    const val GET_CORE_OPTIONS_VERSION = 52
    const val SET_CORE_OPTIONS_DISPLAY = 55
}
