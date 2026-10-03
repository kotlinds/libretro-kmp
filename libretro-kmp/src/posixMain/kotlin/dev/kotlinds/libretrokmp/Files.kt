package dev.kotlinds.libretrokmp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

@OptIn(ExperimentalForeignApi::class)
internal actual fun readFileBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: error("Can't open $path")
    try {
        fseek(file, 0, SEEK_END)
        val size: Int = ftell(file).convert()
        fseek(file, 0, SEEK_SET)
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { fread(it.addressOf(0), 1u, size.convert(), file) }
        return bytes
    } finally {
        fclose(file)
    }
}
