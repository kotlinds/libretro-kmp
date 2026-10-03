package dev.kotlinds.libretrokmp

/**
 * JNI bridge to the native `libretro_jni` library bundled with this module (see
 * `src/androidMain/cpp/libretro_jni.c`). It holds the single open core of the process.
 *
 * Native pointers (the environment callback's `data`) are passed as [Long] values.
 * This object is internal — use [LibretroCore] instead.
 */
internal object LibretroJni {
    init {
        System.loadLibrary("libretro_jni")
    }

    /** Opens the core, registers [callbacks] (LibretroCore's private Callbacks) and calls retro_init(). */
    external fun open(corePath: String, callbacks: Any): Boolean
    external fun close()

    /** {library_name, library_version, valid_extensions, need_fullpath "1"/"0", block_extract "1"/"0"}. */
    external fun systemInfo(): Array<String>

    /** [data] is the game's bytes when the core doesn't load from the path, null otherwise. */
    external fun loadGame(path: String, data: ByteArray?): Boolean

    /** {base_width, base_height, max_width, max_height, aspect_ratio, fps, sample_rate}. */
    external fun avInfo(): DoubleArray

    external fun run()
    external fun reset()
    external fun setControllerPortDevice(port: Int, device: Int)

    external fun memorySize(type: Int): Long
    external fun readMemory(type: Int): ByteArray?
    external fun writeMemory(type: Int, data: ByteArray): Boolean
    external fun saveState(): ByteArray?
    external fun loadState(state: ByteArray): Boolean

    // Helpers for the environment callback's `data` pointer
    external fun envReadInt(data: Long): Int
    external fun envWriteInt(data: Long, value: Int)
    external fun envWriteBool(data: Long, value: Boolean)
    external fun envWriteString(data: Long, value: String)
    external fun envReadVariableKey(data: Long): String?
    external fun envWriteVariableValue(data: Long, value: String)
    external fun envWriteLogCallback(data: Long)
    external fun envReadAvInfo(data: Long): DoubleArray
    external fun envReadGeometry(data: Long): DoubleArray
}
