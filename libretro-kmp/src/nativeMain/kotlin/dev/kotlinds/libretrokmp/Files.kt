package dev.kotlinds.libretrokmp

/**
 * Reads a whole file (used to pass games in memory to cores that ask for it).
 *
 * Implemented per platform family: the C `long` used by `ftell` is 64-bit on POSIX systems but
 * 32-bit on Windows, so this can't be shared at the `nativeMain` level.
 */
internal expect fun readFileBytes(path: String): ByteArray
