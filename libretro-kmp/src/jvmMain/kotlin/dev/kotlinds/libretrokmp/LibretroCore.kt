package dev.kotlinds.libretrokmp

import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import java.io.File

/**
 * JVM implementation: the core is loaded with JNA and its callbacks are JNA callbacks.
 */
actual class LibretroCore actual constructor(corePath: String, private val frontend: LibretroFrontend) : AutoCloseable {

    private val lib: LibretroLib = Native.load(corePath, LibretroLib::class.java, loadOptions(Platform.getOSType()))
    private val environment = Environment(frontend)

    /** Native strings handed to the core must outlive the call, so they are kept here. */
    private val nativeStrings = HashMap<String, Memory>()

    /** Keeps the game bytes alive while the core runs (some cores read the buffer lazily). */
    private var gameData: Memory? = null

    private var audioBuffer = ShortArray(4096)
    private var frameBuffer = ByteArray(0)
    private var loadedAvInfo: SystemAvInfo? = null

    // Callbacks are stored in fields: if they were garbage collected, the core would call freed memory.
    // The environment callback returns a C `bool` (1 byte): JNA would marshal a Kotlin Boolean as a
    // 4-byte int (true = -1), so it returns a Byte explicitly.
    private val environmentCallback = EnvironmentCallback { cmd, data ->
        if (environment.handle(cmd, data?.let(::JnaEnvironmentData))) 1 else 0
    }
    private val videoCallback = VideoRefreshCallback(::onVideoRefresh)
    private val audioSampleCallback = AudioSampleCallback { left, right -> frontend.onAudio(shortArrayOf(left, right), 1) }
    private val audioBatchCallback = AudioSampleBatchCallback(::onAudioBatch)
    private val inputPollCallback = InputPollCallback { frontend.onInputPoll() }
    private val inputStateCallback = InputStateCallback(frontend::inputState)
    private val logCallback = LogCallback { level, format ->
        format?.let { frontend.onLog(LogLevel.fromId(level), it.trimEnd()) }
    }

    actual val systemInfo: SystemInfo = RetroSystemInfo().also { lib.retro_get_system_info(it) }.let {
        SystemInfo(
            libraryName = it.library_name.orEmpty(),
            libraryVersion = it.library_version.orEmpty(),
            validExtensions = it.valid_extensions.orEmpty(),
            needFullPath = it.need_fullpath,
            blockExtract = it.block_extract,
        )
    }

    actual val avInfo: SystemAvInfo
        get() = (environment.avInfo ?: loadedAvInfo ?: error("No game loaded")).let { info ->
            environment.geometry?.let { info.copy(geometry = it) } ?: info
        }

    init {
        require(lib.retro_api_version() == 1) { "Unsupported libretro API version ${lib.retro_api_version()}" }
        // The environment callback must be set before retro_init(), the others before retro_run().
        lib.retro_set_environment(environmentCallback)
        lib.retro_init()
        lib.retro_set_video_refresh(videoCallback)
        lib.retro_set_audio_sample(audioSampleCallback)
        lib.retro_set_audio_sample_batch(audioBatchCallback)
        lib.retro_set_input_poll(inputPollCallback)
        lib.retro_set_input_state(inputStateCallback)
    }

    actual fun loadGame(gamePath: String) {
        val info = RetroGameInfo().apply {
            path = File(gamePath).absolutePath
            if (!systemInfo.needFullPath) {
                val bytes = File(gamePath).readBytes()
                gameData = Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
                data = gameData
                size = bytes.size.toLong()
            }
        }
        check(lib.retro_load_game(info)) { "The core failed to load $gamePath" }
        loadedAvInfo = RetroSystemAvInfo().also { lib.retro_get_system_av_info(it) }.toSystemAvInfo()
    }

    actual fun run() = lib.retro_run()

    actual fun reset() = lib.retro_reset()

    actual fun setControllerPortDevice(port: Int, device: Int) = lib.retro_set_controller_port_device(port, device)

    actual fun memorySize(type: MemoryType): Long = lib.retro_get_memory_size(type.id)

    actual fun readMemory(type: MemoryType): ByteArray? {
        val size = lib.retro_get_memory_size(type.id)
        val pointer = lib.retro_get_memory_data(type.id) ?: return null
        if (size <= 0) return null
        return pointer.getByteArray(0, size.toInt())
    }

    actual fun writeMemory(type: MemoryType, data: ByteArray): Boolean {
        val size = lib.retro_get_memory_size(type.id)
        val pointer = lib.retro_get_memory_data(type.id) ?: return false
        if (size <= 0) return false
        pointer.write(0, data, 0, minOf(data.size.toLong(), size).toInt())
        return true
    }

    actual fun saveState(): ByteArray? {
        val size = lib.retro_serialize_size()
        if (size <= 0) return null
        val buffer = ByteArray(size.toInt())
        return if (lib.retro_serialize(buffer, size)) buffer else null
    }

    actual fun loadState(state: ByteArray): Boolean = lib.retro_unserialize(state, state.size.toLong())

    actual override fun close() {
        lib.retro_unload_game()
        lib.retro_deinit()
    }

    private fun onVideoRefresh(data: Pointer?, width: Int, height: Int, pitch: Long) {
        // A null frame means "same as the previous frame" (frame duping): nothing to do.
        if (data == null || width <= 0 || height <= 0) return
        val size = (pitch * height).toInt()
        if (frameBuffer.size < size) frameBuffer = ByteArray(size)
        data.read(0, frameBuffer, 0, size)
        frontend.onVideoFrame(PixelConverter.convert(environment.pixelFormat, width, height, pitch.toInt(), frameBuffer))
    }

    private fun onAudioBatch(data: Pointer, frames: Long): Long {
        val samples = (frames * 2).toInt() // stereo: interleaved left/right
        if (audioBuffer.size < samples) audioBuffer = ShortArray(samples)
        data.read(0, audioBuffer, 0, samples)
        frontend.onAudio(audioBuffer, frames.toInt())
        return frames
    }

    private fun nativeString(value: String): Memory = nativeStrings.getOrPut(value) {
        Memory(value.encodeToByteArray().size + 1L).also { it.setString(0, value) }
    }

    /** [EnvironmentData] over a JNA pointer. */
    private inner class JnaEnvironmentData(private val pointer: Pointer) : EnvironmentData {
        override fun readInt(): Int = pointer.getInt(0)
        override fun writeInt(value: Int) = pointer.setInt(0, value)
        override fun writeBool(value: Boolean) = pointer.setByte(0, if (value) 1 else 0)
        override fun writeString(value: String) = pointer.setPointer(0, nativeString(value))
        override fun readVariableKey(): String? = RetroVariable(pointer).key
        override fun writeVariableValue(value: String) = pointer.setPointer(Native.POINTER_SIZE.toLong(), nativeString(value))
        override fun writeLogCallback() = pointer.setPointer(0, CallbackReference.getFunctionPointer(logCallback))
        override fun readAvInfo(): SystemAvInfo = RetroSystemAvInfo(pointer).toSystemAvInfo()
        override fun readGeometry(): GameGeometry = RetroGameGeometry(pointer).toGameGeometry()
    }
}

private fun RetroGameGeometry.toGameGeometry() = GameGeometry(base_width, base_height, max_width, max_height, aspect_ratio)

private fun RetroSystemAvInfo.toSystemAvInfo() = SystemAvInfo(
    geometry = geometry.toGameGeometry(),
    timing = SystemTiming(timing.fps, timing.sample_rate),
)

/**
 * JNA options for loading the core: on POSIX systems, `dlopen` flags `RTLD_NOW | RTLD_LOCAL`, like the native targets
 * (posixMain `SharedLibrary`) and Android (`libretro_jni.c`). JNA's default is `RTLD_LAZY | RTLD_GLOBAL`: a core's
 * symbols then join the process's global namespace, and a second instance opened from another copy of the core file
 * can bind to the first one's functions and globals (two instances share one state). `RTLD_LOCAL` keeps each image's
 * symbols to itself. The values differ per OS (`RTLD_LOCAL` is 0 on Linux, 4 on Apple platforms).
 *
 * On Windows no flags are given: JNA passes them to `LoadLibraryExW`, where they mean something else (`0x2` is
 * `LOAD_LIBRARY_AS_DATAFILE`: the DLL is mapped as data, and looking up any function then fails with "The specified
 * module could not be found"). Windows keeps modules loaded from different files apart anyway.
 *
 * @param osType JNA's `Platform.getOSType()`.
 */
internal fun loadOptions(osType: Int): Map<String, Any> = when (osType) {
    Platform.WINDOWS, Platform.WINDOWSCE -> emptyMap()
    Platform.MAC -> mapOf(Library.OPTION_OPEN_FLAGS to (RTLD_NOW or APPLE_RTLD_LOCAL))
    else -> mapOf(Library.OPTION_OPEN_FLAGS to (RTLD_NOW or LINUX_RTLD_LOCAL))
}

private const val RTLD_NOW = 0x2
private const val APPLE_RTLD_LOCAL = 0x4
private const val LINUX_RTLD_LOCAL = 0x0
