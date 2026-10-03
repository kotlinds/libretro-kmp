package dev.kotlinds.libretrokmp

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.FreeLibrary
import platform.windows.GetLastError
import platform.windows.GetProcAddress
import platform.windows.HMODULE
import platform.windows.LoadLibraryW

/** Windows: `LoadLibraryW` / `GetProcAddress`. */
@OptIn(ExperimentalForeignApi::class)
internal actual class SharedLibrary actual constructor(path: String) {

    private val handle: HMODULE = LoadLibraryW(path) ?: error("Can't load $path (error ${GetLastError()})")

    actual fun symbol(name: String): COpaquePointer? = GetProcAddress(handle, name)

    actual fun close() {
        FreeLibrary(handle)
    }
}
