package dev.kotlinds.libretrokmp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * Two instances of the same core in one process, each opened from its own copy of the core file (a libretro core
 * keeps its state in globals, so a second instance needs a second image of the library), must not share anything:
 * running one leaves the other's memory untouched. With JNA's default open flags (RTLD_GLOBAL) the second image could
 * bind to the first one's symbols, so the second instance ran on the first one's globals.
 *
 * Runs only when `LIBRETRO_TEST_CORE` and `LIBRETRO_TEST_GAME` are set (see [runCoreScenario]).
 */
class IsolatedInstancesJvmTest {

    private fun frontend(directory: String) = object : LibretroFrontend {
        override val systemDirectory = directory
        override val saveDirectory = directory
        override fun onVideoFrame(frame: VideoFrame) = Unit
        override fun onAudio(samples: ShortArray, frames: Int) = Unit
        override fun onInputPoll() = Unit
    }

    @Test
    fun twoCopiesOfACoreDoNotShareTheirState() {
        val core = System.getenv("LIBRETRO_TEST_CORE") ?: return println("LIBRETRO_TEST_CORE not set: skipped")
        val game = System.getenv("LIBRETRO_TEST_GAME") ?: return println("LIBRETRO_TEST_GAME not set: skipped")
        val directory = Files.createTempDirectory("libretro-kmp-isolation")
        val extension = core.substringAfterLast('.', "")
        val copyA = Files.copy(Path.of(core), directory.resolve("core-a.$extension"), StandardCopyOption.REPLACE_EXISTING)
        val copyB = Files.copy(Path.of(core), directory.resolve("core-b.$extension"), StandardCopyOption.REPLACE_EXISTING)
        val first = LibretroCore(copyA.toString(), frontend(directory.resolve("a").also(Files::createDirectories).toString()))
        val second = LibretroCore(copyB.toString(), frontend(directory.resolve("b").also(Files::createDirectories).toString()))
        try {
            first.loadGame(game)
            repeat(120) { first.run() }
            val before = assertNotNull(first.readMemory(MemoryType.SYSTEM_RAM), "the core exposes its RAM")

            second.loadGame(game)
            repeat(300) { second.run() }

            assertContentEquals(before, first.readMemory(MemoryType.SYSTEM_RAM), "running the second instance changed the first one's RAM")
        } finally {
            second.close()
            first.close()
        }
    }
}
