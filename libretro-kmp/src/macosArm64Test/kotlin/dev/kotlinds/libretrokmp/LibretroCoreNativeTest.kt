package dev.kotlinds.libretrokmp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

@OptIn(ExperimentalForeignApi::class)
class LibretroCoreNativeTest {

    @Test
    fun runsARealCore() {
        val core = getenv("LIBRETRO_TEST_CORE")?.toKString() ?: return println("LIBRETRO_TEST_CORE not set: skipped")
        val game = getenv("LIBRETRO_TEST_GAME")?.toKString() ?: return println("LIBRETRO_TEST_GAME not set: skipped")
        runCoreScenario(core, game, getenv("TMPDIR")?.toKString() ?: "/tmp")
    }
}
