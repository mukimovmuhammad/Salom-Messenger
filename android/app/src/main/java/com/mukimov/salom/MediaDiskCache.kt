package com.mukimov.salom

import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex

internal class MediaDiskCache(
    private val folder: File,
    private val legacy: File,
    private val byteLimit: Long = 512L * 1024 * 1024
) {
    fun destination(id: String): File {
        folder.mkdirs()
        return File(folder, UUID.fromString(id).toString())
    }

    fun reuse(id: String, size: Long, destination: File): Boolean {
        if (destination.isFile && destination.length() == size) {
            destination.setLastModified(System.currentTimeMillis())
            return true
        }
        val previous = File(legacy, UUID.fromString(id).toString())
        if (!previous.isFile || previous.length() != size) return false
        return previous.renameTo(destination)
    }

    fun prune(keep: File) = synchronized(pruneLock) {
        val files = folder.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
        var bytes = files.sumOf { it.length() }
        if (bytes <= byteLimit) return@synchronized
        for (old in files.sortedBy { it.lastModified() }) {
            if (bytes <= byteLimit) break
            if (old == keep) continue
            val gate = gate(old)
            if (!gate.tryLock()) continue
            try {
                val length = old.length()
                if (old.delete()) bytes -= length
            } finally {
                gate.unlock()
            }
        }
    }

    companion object {
        private val pruneLock = Any()
        private val locks = Array(64) { Mutex() }

        fun gate(file: File): Mutex = locks[(file.path.hashCode() and Int.MAX_VALUE) % locks.size]
    }
}
