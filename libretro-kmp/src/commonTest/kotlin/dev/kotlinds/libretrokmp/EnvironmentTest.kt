package dev.kotlinds.libretrokmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentTest {

    private val frontend = object : LibretroFrontend {
        override val systemDirectory = "/system"
        override val saveDirectory = "/saves"
        override fun variable(key: String) = if (key == "core_option") "enabled" else null
    }

    /** Records what the environment writes, like a native `data` pointer would. */
    private class FakeData(var int: Int = 0, val key: String? = null) : EnvironmentData {
        var bool: Boolean? = null
        var string: String? = null
        var value: String? = null
        var logInstalled = false
        override fun readInt() = int
        override fun writeInt(value: Int) { int = value }
        override fun writeBool(value: Boolean) { bool = value }
        override fun writeString(value: String) { string = value }
        override fun readVariableKey() = key
        override fun writeVariableValue(value: String) { this.value = value }
        override fun writeLogCallback() { logInstalled = true }
        override fun readAvInfo() = SystemAvInfo(GameGeometry(256, 384, 256, 384, 0f), SystemTiming(60.0, 32768.0))
        override fun readGeometry() = GameGeometry(320, 240, 320, 240, 4f / 3)
    }

    private val environment = Environment(frontend)

    @Test
    fun answersDirectories() {
        val data = FakeData()
        assertTrue(environment.handle(EnvironmentCommand.GET_SYSTEM_DIRECTORY, data))
        assertEquals("/system", data.string)
        assertTrue(environment.handle(EnvironmentCommand.GET_SAVE_DIRECTORY, data))
        assertEquals("/saves", data.string)
    }

    @Test
    fun acceptsKnownPixelFormatsOnly() {
        assertTrue(environment.handle(EnvironmentCommand.SET_PIXEL_FORMAT, FakeData(int = PixelFormat.XRGB8888.id)))
        assertEquals(PixelFormat.XRGB8888, environment.pixelFormat)
        assertFalse(environment.handle(EnvironmentCommand.SET_PIXEL_FORMAT, FakeData(int = 42)))
        assertEquals(PixelFormat.XRGB8888, environment.pixelFormat)
    }

    @Test
    fun answersCoreOptionsFromTheFrontend() {
        val known = FakeData(key = "core_option")
        assertTrue(environment.handle(EnvironmentCommand.GET_VARIABLE, known))
        assertEquals("enabled", known.value)
        assertFalse(environment.handle(EnvironmentCommand.GET_VARIABLE, FakeData(key = "unknown")))
    }

    @Test
    fun ignoresTheExperimentalFlag() {
        val data = FakeData()
        assertTrue(environment.handle(EnvironmentCommand.GET_AUDIO_VIDEO_ENABLE or EnvironmentCommand.EXPERIMENTAL, data))
        assertEquals(0b11, data.int)
    }

    @Test
    fun rememberAvInfoAndGeometryChanges() {
        assertTrue(environment.handle(EnvironmentCommand.SET_SYSTEM_AV_INFO, FakeData()))
        assertEquals(32768.0, environment.avInfo?.timing?.sampleRate)
        assertTrue(environment.handle(EnvironmentCommand.SET_GEOMETRY, FakeData()))
        assertEquals(320, environment.geometry?.baseWidth)
    }

    @Test
    fun refusesUnsupportedCommandsAndMissingData() {
        assertFalse(environment.handle(9999, FakeData()))
        assertFalse(environment.handle(EnvironmentCommand.GET_SYSTEM_DIRECTORY, null))
    }
}
