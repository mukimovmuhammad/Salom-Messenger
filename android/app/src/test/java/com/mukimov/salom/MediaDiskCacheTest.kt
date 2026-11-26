package com.mukimov.salom

import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacyFileIsReusedAfterReopenAndIncompleteFileIsIgnored() {
        val received = temporary.newFolder()
        val legacy = temporary.newFolder()
        val id = UUID.randomUUID().toString()
        File(legacy, id).writeBytes(byteArrayOf(1, 2, 3))
        val first = MediaDiskCache(received, legacy)
        val file = first.destination(id)
        assertFalse(first.reuse(id, 4, file))
        assertTrue(first.reuse(id, 3, file))
        val reopened = MediaDiskCache(received, legacy)
        assertTrue(reopened.reuse(id, 3, reopened.destination(id)))
        assertFalse(reopened.reuse(id, 4, reopened.destination(id)))
    }

    @Test fun evictsOldestCompletedFileAndKeepsNewFileAndTransfer() {
        val cache = MediaDiskCache(temporary.newFolder(), temporary.newFolder(), byteLimit = 6)
        val old = cache.destination(UUID.randomUUID().toString())
        val recent = cache.destination(UUID.randomUUID().toString())
        val current = cache.destination(UUID.randomUUID().toString())
        for ((index, file) in listOf(old, recent, current).withIndex()) {
            file.writeBytes(byteArrayOf(1, 2, 3))
            file.setLastModified(1000L * (index + 1))
        }
        val transfer = File(current.parentFile, "download.part").apply { writeBytes(byteArrayOf(4)) }
        cache.prune(current)
        assertFalse(old.exists())
        assertTrue(recent.exists() && current.exists() && transfer.exists())
    }
}
