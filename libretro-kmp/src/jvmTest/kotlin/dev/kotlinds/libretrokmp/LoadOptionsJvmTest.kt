package dev.kotlinds.libretrokmp

import com.sun.jna.Library
import com.sun.jna.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JNA options a core is loaded with, per OS (see [loadOptions]). */
class LoadOptionsJvmTest {

    @Test
    fun windowsGetsNoOpenFlags() {
        // LoadLibraryExW would read RTLD_NOW (0x2) as LOAD_LIBRARY_AS_DATAFILE: no function could be looked up.
        assertTrue(loadOptions(Platform.WINDOWS).isEmpty())
        assertTrue(loadOptions(Platform.WINDOWSCE).isEmpty())
    }

    @Test
    fun macGetsRtldNowLocal() {
        assertEquals(mapOf(Library.OPTION_OPEN_FLAGS to 0x6), loadOptions(Platform.MAC))
    }

    @Test
    fun linuxGetsRtldNowLocal() {
        assertEquals(mapOf(Library.OPTION_OPEN_FLAGS to 0x2), loadOptions(Platform.LINUX))
    }
}
