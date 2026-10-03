package dev.kotlinds.libretrokmp

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.RTLD_LOCAL
import platform.posix.RTLD_NOW
import platform.posix.dlclose
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

/** macOS, iOS and Linux: `dlopen` / `dlsym`. */
@OptIn(ExperimentalForeignApi::class)
internal actual class SharedLibrary actual constructor(path: String) {

    private val handle: COpaquePointer = dlopen(path, RTLD_NOW or RTLD_LOCAL)
        ?: error("Can't load $path: ${dlerror()?.toKString()}")

    actual fun symbol(name: String): COpaquePointer? = dlsym(handle, name)

    actual fun close() {
        dlclose(handle)
    }
}
