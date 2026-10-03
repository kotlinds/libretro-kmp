package dev.kotlinds.libretrokmp

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end scenario against a real core, shared by the platform integration tests: open the core,
 * load a game, run frames, check video/audio/memory/states, close.
 *
 * Platform tests run it only when `LIBRETRO_TEST_CORE` (path to a core) and `LIBRETRO_TEST_GAME`
 * (path to a game it supports) are set, e.g. the melonDS core and a Nintendo DS game.
 */
internal fun runCoreScenario(corePath: String, gamePath: String, directory: String) {
    var frames = 0
    var lastFrame: VideoFrame? = null
    var audioFrames = 0
    var polls = 0
    val frontend = object : LibretroFrontend {
        override val systemDirectory = directory
        override val saveDirectory = directory
        override fun onVideoFrame(frame: VideoFrame) {
            frames++
            lastFrame = frame
        }
        override fun onAudio(samples: ShortArray, frames: Int) {
            audioFrames += frames
        }
        override fun onInputPoll() {
            polls++
        }
    }

    val core = LibretroCore(corePath, frontend)
    try {
        assertTrue(core.systemInfo.libraryName.isNotEmpty(), "the core reports its name")
        core.loadGame(gamePath)
        assertTrue(core.avInfo.timing.fps > 0, "timings are available after loading")
        core.setControllerPortDevice(0, Device.JOYPAD)

        repeat(120) { core.run() }
        assertTrue(frames > 0, "video frames were produced")
        assertTrue(audioFrames > 0, "audio was produced")
        assertTrue(polls > 0, "inputs were polled")
        val frame = assertNotNull(lastFrame)
        assertEquals(frame.width * frame.height, frame.pixels.size)

        val ram = assertNotNull(core.readMemory(MemoryType.SYSTEM_RAM), "the core exposes its RAM")
        assertEquals(core.memorySize(MemoryType.SYSTEM_RAM), ram.size.toLong())

        val state = assertNotNull(core.saveState(), "the core supports save states")
        repeat(30) { core.run() }
        assertTrue(core.loadState(state), "the state can be restored")
        core.reset()
        core.run()
    } finally {
        core.close()
    }
}
