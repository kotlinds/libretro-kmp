package dev.kotlinds.libretrokmp

import java.io.File

/**
 * Android implementation: the core is opened by the JNI bridge ([LibretroJni]), which calls back
 * into [Callbacks].
 */
actual class LibretroCore actual constructor(corePath: String, private val frontend: LibretroFrontend) : AutoCloseable {

    private val environment = Environment(frontend)
    private var loadedAvInfo: SystemAvInfo? = null

    actual val systemInfo: SystemInfo

    actual val avInfo: SystemAvInfo
        get() = (environment.avInfo ?: loadedAvInfo ?: error("No game loaded")).let { info ->
            environment.geometry?.let { info.copy(geometry = it) } ?: info
        }

    init {
        check(LibretroJni.open(corePath, Callbacks())) { "Can't open the core $corePath (see logcat, tag LibretroJNI)" }
        systemInfo = LibretroJni.systemInfo().let {
            SystemInfo(it[0], it[1], it[2], needFullPath = it[3] == "1", blockExtract = it[4] == "1")
        }
    }

    actual fun loadGame(gamePath: String) {
        val data = if (systemInfo.needFullPath) null else File(gamePath).readBytes()
        check(LibretroJni.loadGame(File(gamePath).absolutePath, data)) { "The core failed to load $gamePath" }
        loadedAvInfo = LibretroJni.avInfo().toSystemAvInfo()
    }

    actual fun run() = LibretroJni.run()
    actual fun reset() = LibretroJni.reset()
    actual fun setControllerPortDevice(port: Int, device: Int) = LibretroJni.setControllerPortDevice(port, device)
    actual fun memorySize(type: MemoryType): Long = LibretroJni.memorySize(type.id)
    actual fun readMemory(type: MemoryType): ByteArray? = LibretroJni.readMemory(type.id)
    actual fun writeMemory(type: MemoryType, data: ByteArray): Boolean = LibretroJni.writeMemory(type.id, data)
    actual fun saveState(): ByteArray? = LibretroJni.saveState()
    actual fun loadState(state: ByteArray): Boolean = LibretroJni.loadState(state)
    actual override fun close() = LibretroJni.close()

    /**
     * Methods called from C (libretro_jni.c), looked up by name and signature: they live in a private
     * class so they keep their exact JVM names without being part of the public API.
     */
    private inner class Callbacks {
        fun environment(command: Int, data: Long): Boolean =
            environment.handle(command, if (data == 0L) null else JniEnvironmentData(data))

        fun onVideoRefresh(data: ByteArray, width: Int, height: Int, pitch: Int) =
            frontend.onVideoFrame(PixelConverter.convert(environment.pixelFormat, width, height, pitch, data))

        fun onAudioBatch(samples: ShortArray, frames: Int) = frontend.onAudio(samples, frames)
        fun onInputPoll() = frontend.onInputPoll()
        fun onInputState(port: Int, device: Int, index: Int, id: Int): Short = frontend.inputState(port, device, index, id)
        fun onLog(level: Int, message: String) = frontend.onLog(LogLevel.fromId(level), message.trimEnd())
    }

    /** [EnvironmentData] over the native pointer, through the JNI helpers. */
    private class JniEnvironmentData(private val pointer: Long) : EnvironmentData {
        override fun readInt(): Int = LibretroJni.envReadInt(pointer)
        override fun writeInt(value: Int) = LibretroJni.envWriteInt(pointer, value)
        override fun writeBool(value: Boolean) = LibretroJni.envWriteBool(pointer, value)
        override fun writeString(value: String) = LibretroJni.envWriteString(pointer, value)
        override fun readVariableKey(): String? = LibretroJni.envReadVariableKey(pointer)
        override fun writeVariableValue(value: String) = LibretroJni.envWriteVariableValue(pointer, value)
        override fun writeLogCallback() = LibretroJni.envWriteLogCallback(pointer)
        override fun readAvInfo(): SystemAvInfo = LibretroJni.envReadAvInfo(pointer).toSystemAvInfo()
        override fun readGeometry(): GameGeometry = LibretroJni.envReadGeometry(pointer).toGameGeometry()
    }
}

private fun DoubleArray.toGameGeometry() =
    GameGeometry(this[0].toInt(), this[1].toInt(), this[2].toInt(), this[3].toInt(), this[4].toFloat())

private fun DoubleArray.toSystemAvInfo() = SystemAvInfo(toGameGeometry(), SystemTiming(this[5], this[6]))
