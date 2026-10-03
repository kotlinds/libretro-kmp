package dev.kotlinds.libretrokmp

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * A shared library opened at runtime: `dlopen` on POSIX systems, `LoadLibrary` on Windows.
 */
@OptIn(ExperimentalForeignApi::class)
internal expect class SharedLibrary(path: String) {
    /** Address of an exported symbol, or null if it doesn't exist. */
    fun symbol(name: String): COpaquePointer?

    fun close()
}
