package dev.kotlinds.libretrokmp

import java.nio.file.Files
import kotlin.test.Test

class LibretroCoreJvmTest {

    @Test
    fun runsARealCore() {
        val core = System.getenv("LIBRETRO_TEST_CORE") ?: return println("LIBRETRO_TEST_CORE not set: skipped")
        val game = System.getenv("LIBRETRO_TEST_GAME") ?: return println("LIBRETRO_TEST_GAME not set: skipped")
        runCoreScenario(core, game, Files.createTempDirectory("libretro-kmp").toString())
    }
}
