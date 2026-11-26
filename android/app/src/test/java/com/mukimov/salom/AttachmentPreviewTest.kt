package com.mukimov.salom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentPreviewTest {
    @get:Rule val files = TemporaryFolder()
    private fun kind(name: String, mime: String) = Attachment("test", name, mime, 1).previewKind()

    @Test fun recognizesDocumentsFromUploadMetadata() {
        assertEquals(PreviewKind.PDF, kind("Notes.PDF", "application/octet-stream"))
        assertEquals(PreviewKind.TEXT, kind("notes.txt", "text/plain; charset=utf-8"))
        assertEquals(PreviewKind.MARKDOWN, kind("Notes.MD", "text/plain"))
        assertEquals(PreviewKind.MARKDOWN, kind("notes", "text/markdown"))
    }

    @Test fun keepsExternalFormatsOutOfTheViewer() {
        assertEquals(PreviewKind.PHOTO, kind("photo.webp", "image/webp"))
        assertNull(kind("sheet.xlsx", "application/octet-stream"))
        assertNull(kind("page.txt", "text/html"))
        assertNull(kind("image.svg", "image/svg+xml"))
        assertNull(kind("voice.m4a", "audio/mp4"))
    }

    @Test fun paginatesLargeUtf16DocumentsWithoutSplittingAnEmoji() {
        val file = files.newFile()
        file.writeText("x".repeat(96 * 1024 - 1) + "🙂" + "Салом", Charsets.UTF_16)
        val first = file.readTextPage(0)
        assertTrue(first.hasMore)
        assertTrue(first.lines.last().endsWith("🙂"))
        val second = file.readTextPage(1)
        assertEquals(listOf("Салом"), second.lines)
        assertFalse(second.hasMore)
    }
}
