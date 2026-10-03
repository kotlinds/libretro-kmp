package dev.kotlinds.libretrokmp

/**
 * A libretro core (an emulator such as melonDS, mGBA, DeSmuME…) loaded from a shared library at
 * runtime, driven by this frontend.
 *
 * Typical lifecycle:
 * ```
 * val core = LibretroCore("/path/to/melonds_libretro.dylib", frontend)
 * core.loadGame("/path/to/game.nds")
 * while (running) core.run()   // one emulated frame per call; callbacks fire during run()
 * core.close()
 * ```
 *
 * The core talks back through the [LibretroFrontend] given at construction: video frames, audio
 * samples, input queries, directories and options.
 *
 * Threading and instances: a core must be driven from a single thread, and libretro cores keep
 * global state, so only one [LibretroCore] may be open at a time in a process (its callbacks are
 * routed globally on native targets).
 *
 * @param corePath Absolute path to the core's shared library (`.dylib`, `.so`, `.dll`).
 * @param frontend Receives the core's callbacks and answers its questions.
 */
expect class LibretroCore(corePath: String, frontend: LibretroFrontend) : AutoCloseable {

    /** Name, version and supported extensions reported by the core. */
    val systemInfo: SystemInfo

    /** Video geometry and timings, available after [loadGame] (and updated if the core changes them). */
    val avInfo: SystemAvInfo

    /**
     * Loads a game. When the core doesn't need a path ([SystemInfo.needFullPath] is false), the file
     * is read and passed to the core in memory.
     *
     * @throws IllegalStateException if the core refuses the game.
     */
    fun loadGame(gamePath: String)

    /** Emulates exactly one frame. [LibretroFrontend] callbacks are invoked during this call. */
    fun run()

    /** Resets the emulated console. */
    fun reset()

    /** Sets the device plugged in a controller port (see [Device]). */
    fun setControllerPortDevice(port: Int, device: Int)

    /** Size in bytes of a memory region, or 0 if the core doesn't expose it. */
    fun memorySize(type: MemoryType): Long

    /** Copy of a memory region (e.g. the console's main RAM), or null if the core doesn't expose it. */
    fun readMemory(type: MemoryType): ByteArray?

    /** Overwrites the beginning of a memory region with [data]. Returns false if not exposed. */
    fun writeMemory(type: MemoryType, data: ByteArray): Boolean

    /** Serializes the whole emulator state (a "save state"), or null if unsupported. */
    fun saveState(): ByteArray?

    /** Restores a state produced by [saveState]. Returns false on failure. */
    fun loadState(state: ByteArray): Boolean

    /** Unloads the game and deinitializes the core. The instance must not be used afterwards. */
    override fun close()
}
