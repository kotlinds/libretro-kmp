package dev.kotlinds.libretrokmp

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import libretro.native.libretro_kmp_log_function
import libretro.native.libretro_kmp_set_log_sink
import libretro.native.retro_game_geometry
import libretro.native.retro_game_info
import libretro.native.retro_system_av_info
import libretro.native.retro_system_info
import libretro.native.retro_variable
import platform.posix.memcpy

/**
 * The open core, used by the C callbacks: libretro callbacks carry no user data, so they are
 * routed to the single open [LibretroCore] of the process.
 */
@OptIn(ExperimentalForeignApi::class)
private var active: LibretroCore? = null

/**
 * Native implementation (macOS, iOS, Linux, Windows): the core is opened with `dlopen` /
 * `LoadLibrary`, its functions are called through their addresses, and the callbacks are static C
 * functions routed to the open core.
 */
@OptIn(ExperimentalForeignApi::class)
actual class LibretroCore actual constructor(corePath: String, private val frontend: LibretroFrontend) : AutoCloseable {

    private val library = SharedLibrary(corePath)

    /** Native memory that must outlive calls: strings given to the core, the game data. */
    private val arena = Arena()
    private val strings = HashMap<String, CPointer<ByteVar>>()

    internal val environment = Environment(frontend)
    private var loadedAvInfo: SystemAvInfo? = null
    private var audioBuffer = ShortArray(4096)

    actual val systemInfo: SystemInfo

    actual val avInfo: SystemAvInfo
        get() = (environment.avInfo ?: loadedAvInfo ?: error("No game loaded")).let { info ->
            environment.geometry?.let { info.copy(geometry = it) } ?: info
        }

    init {
        check(active == null) { "Only one LibretroCore can be open at a time" }
        active = this
        try {
            val version = function<() -> UInt>("retro_api_version")()
            require(version == 1u) { "Unsupported libretro API version $version" }
            libretro_kmp_set_log_sink(logSink)
            // The environment callback must be set before retro_init(), the others before retro_run().
            setter("retro_set_environment")(environmentCallback)
            function<() -> Unit>("retro_init")()
            setter("retro_set_video_refresh")(videoCallback)
            setter("retro_set_audio_sample")(audioSampleCallback)
            setter("retro_set_audio_sample_batch")(audioBatchCallback)
            setter("retro_set_input_poll")(inputPollCallback)
            setter("retro_set_input_state")(inputStateCallback)
            systemInfo = memScoped {
                val info = alloc<retro_system_info>()
                function<(CPointer<retro_system_info>) -> Unit>("retro_get_system_info")(info.ptr)
                SystemInfo(
                    libraryName = info.library_name?.toKString().orEmpty(),
                    libraryVersion = info.library_version?.toKString().orEmpty(),
                    validExtensions = info.valid_extensions?.toKString().orEmpty(),
                    needFullPath = info.need_fullpath,
                    blockExtract = info.block_extract,
                )
            }
        } catch (error: Throwable) {
            active = null
            arena.clear()
            library.close()
            throw error
        }
    }

    actual fun loadGame(gamePath: String) {
        val loaded = memScoped {
            val info = alloc<retro_game_info>()
            info.path = persistentString(gamePath)
            info.meta = null
            if (!systemInfo.needFullPath) {
                val bytes = readFileBytes(gamePath)
                val data = arena.allocArray<ByteVar>(bytes.size)
                bytes.usePinned { memcpy(data, it.addressOf(0), bytes.size.toULong()) }
                info.data = data
                info.size = bytes.size.toULong()
            } else {
                info.data = null
                info.size = 0u
            }
            function<(CPointer<retro_game_info>) -> Boolean>("retro_load_game")(info.ptr)
        }
        check(loaded) { "The core failed to load $gamePath" }
        loadedAvInfo = memScoped {
            val info = alloc<retro_system_av_info>()
            function<(CPointer<retro_system_av_info>) -> Unit>("retro_get_system_av_info")(info.ptr)
            info.toSystemAvInfo()
        }
    }

    actual fun run() = function<() -> Unit>("retro_run")()

    actual fun reset() = function<() -> Unit>("retro_reset")()

    actual fun setControllerPortDevice(port: Int, device: Int) =
        function<(UInt, UInt) -> Unit>("retro_set_controller_port_device")(port.toUInt(), device.toUInt())

    actual fun memorySize(type: MemoryType): Long =
        function<(UInt) -> ULong>("retro_get_memory_size")(type.id.toUInt()).toLong()

    actual fun readMemory(type: MemoryType): ByteArray? {
        val size = memorySize(type)
        val data = function<(UInt) -> COpaquePointer?>("retro_get_memory_data")(type.id.toUInt()) ?: return null
        if (size <= 0) return null
        return data.readBytes(size.toInt())
    }

    actual fun writeMemory(type: MemoryType, data: ByteArray): Boolean {
        val size = memorySize(type)
        val target = function<(UInt) -> COpaquePointer?>("retro_get_memory_data")(type.id.toUInt()) ?: return false
        if (size <= 0 || data.isEmpty()) return false
        val count = minOf(data.size.toLong(), size)
        data.usePinned { memcpy(target, it.addressOf(0), count.toULong()) }
        return true
    }

    actual fun saveState(): ByteArray? {
        val size = function<() -> ULong>("retro_serialize_size")()
        if (size == 0uL) return null
        val buffer = ByteArray(size.toInt())
        val saved = buffer.usePinned { function<(COpaquePointer, ULong) -> Boolean>("retro_serialize")(it.addressOf(0), size) }
        return if (saved) buffer else null
    }

    actual fun loadState(state: ByteArray): Boolean {
        if (state.isEmpty()) return false
        return state.usePinned {
            function<(COpaquePointer, ULong) -> Boolean>("retro_unserialize")(it.addressOf(0), state.size.toULong())
        }
    }

    actual override fun close() {
        function<() -> Unit>("retro_unload_game")()
        function<() -> Unit>("retro_deinit")()
        libretro_kmp_set_log_sink(null)
        active = null
        arena.clear()
        library.close()
    }

    // region Callbacks (called by the core through the static C functions below)

    internal fun onVideoRefresh(data: COpaquePointer?, width: Int, height: Int, pitch: Int) {
        // A null frame means "same as the previous frame" (frame duping): nothing to do.
        if (data == null || width <= 0 || height <= 0) return
        val bytes = data.readBytes(pitch * height)
        frontend.onVideoFrame(PixelConverter.convert(environment.pixelFormat, width, height, pitch, bytes))
    }

    internal fun onAudioBatch(data: CPointer<ShortVar>?, frames: Int) {
        if (data == null || frames <= 0) return
        val samples = frames * 2 // stereo: interleaved left/right
        if (audioBuffer.size < samples) audioBuffer = ShortArray(samples)
        for (i in 0 until samples) audioBuffer[i] = data[i]
        frontend.onAudio(audioBuffer, frames)
    }

    internal fun onAudioSample(left: Short, right: Short) = frontend.onAudio(shortArrayOf(left, right), 1)
    internal fun onInputPoll() = frontend.onInputPoll()
    internal fun onInputState(port: Int, device: Int, index: Int, id: Int): Short = frontend.inputState(port, device, index, id)
    internal fun onLog(level: Int, message: String) = frontend.onLog(LogLevel.fromId(level), message.trimEnd())

    // endregion

    /** A C string that stays valid until [close]. */
    internal fun persistentString(value: String): CPointer<ByteVar> =
        strings.getOrPut(value) { value.cstr.getPointer(arena) }

    /** Address of a core function, typed. */
    private fun <F : Function<*>> function(name: String): CPointer<CFunction<F>> =
        library.symbol(name)?.reinterpret() ?: error("The core doesn't export $name")

    /** `retro_set_*` functions all take one callback pointer. */
    private fun setter(name: String): CPointer<CFunction<(COpaquePointer?) -> Unit>> = function(name)
}

/** [EnvironmentData] over a cinterop pointer. */
@OptIn(ExperimentalForeignApi::class)
private class NativeEnvironmentData(private val core: LibretroCore, private val pointer: COpaquePointer) : EnvironmentData {
    override fun readInt(): Int = pointer.reinterpret<IntVar>().pointed.value
    override fun writeInt(value: Int) {
        pointer.reinterpret<IntVar>().pointed.value = value
    }

    override fun writeBool(value: Boolean) {
        pointer.reinterpret<ByteVar>().pointed.value = if (value) 1 else 0
    }

    override fun writeString(value: String) {
        pointer.reinterpret<CPointerVar<ByteVar>>().pointed.value = core.persistentString(value)
    }

    override fun readVariableKey(): String? = pointer.reinterpret<retro_variable>().pointed.key?.toKString()

    override fun writeVariableValue(value: String) {
        pointer.reinterpret<retro_variable>().pointed.value = core.persistentString(value)
    }

    override fun writeLogCallback() {
        pointer.reinterpret<CPointerVar<CFunction<*>>>().pointed.value = libretro_kmp_log_function()?.reinterpret()
    }

    override fun readAvInfo(): SystemAvInfo = pointer.reinterpret<retro_system_av_info>().pointed.toSystemAvInfo()
    override fun readGeometry(): GameGeometry = pointer.reinterpret<retro_game_geometry>().pointed.toGameGeometry()
}

// region Static C callbacks: libretro callbacks have no user data, so they go to the open core.

@OptIn(ExperimentalForeignApi::class)
private val environmentCallback = staticCFunction { command: UInt, data: COpaquePointer? ->
    val core = active ?: return@staticCFunction false
    core.environment.handle(command.toInt(), data?.let { NativeEnvironmentData(core, it) })
}

@OptIn(ExperimentalForeignApi::class)
private val videoCallback = staticCFunction { data: COpaquePointer?, width: UInt, height: UInt, pitch: ULong ->
    active?.onVideoRefresh(data, width.toInt(), height.toInt(), pitch.toInt())
    Unit
}

@OptIn(ExperimentalForeignApi::class)
private val audioSampleCallback = staticCFunction { left: Short, right: Short ->
    active?.onAudioSample(left, right)
    Unit
}

@OptIn(ExperimentalForeignApi::class)
private val audioBatchCallback = staticCFunction { data: CPointer<ShortVar>?, frames: ULong ->
    active?.onAudioBatch(data, frames.toInt())
    frames
}

@OptIn(ExperimentalForeignApi::class)
private val inputPollCallback = staticCFunction<Unit> {
    active?.onInputPoll()
}

@OptIn(ExperimentalForeignApi::class)
private val inputStateCallback = staticCFunction { port: UInt, device: UInt, index: UInt, id: UInt ->
    active?.onInputState(port.toInt(), device.toInt(), index.toInt(), id.toInt()) ?: 0
}

@OptIn(ExperimentalForeignApi::class)
private val logSink = staticCFunction { level: Int, message: CPointer<ByteVar>? ->
    message?.toKString()?.let { active?.onLog(level, it) }
    Unit
}

// endregion

@OptIn(ExperimentalForeignApi::class)
private fun retro_game_geometry.toGameGeometry() =
    GameGeometry(base_width.toInt(), base_height.toInt(), max_width.toInt(), max_height.toInt(), aspect_ratio)

@OptIn(ExperimentalForeignApi::class)
private fun retro_system_av_info.toSystemAvInfo() = SystemAvInfo(
    geometry = geometry.toGameGeometry(),
    timing = SystemTiming(timing.fps, timing.sample_rate),
)
