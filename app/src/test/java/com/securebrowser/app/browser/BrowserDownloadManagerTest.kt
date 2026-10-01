package com.securebrowser.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserDownloadManagerTest {

    @Test
    fun parseContentDisposition_standardQuotedFilename() {
        val header = """attachment; filename="report_2026.pdf""""
        val result = BrowserDownloadManager.parseContentDisposition(header)
        assertEquals("report_2026.pdf", result)
    }

    @Test
    fun parseContentDisposition_standardUnquotedFilename() {
        val header = "attachment; filename=presentation.pptx"
        val result = BrowserDownloadManager.parseContentDisposition(header)
        assertEquals("presentation.pptx", result)
    }

    @Test
    fun parseContentDisposition_rfc5987EncodedArabicFilename() {
        // "ملف.pdf" URL-encoded in UTF-8
        val header = "attachment; filename*=UTF-8''%D9%85%D9%84%D9%81.pdf"
        val result = BrowserDownloadManager.parseContentDisposition(header)
        assertEquals("ملف.pdf", result)
    }

    @Test
    fun parseContentDisposition_rfc5987WithSpaces() {
        val header = "attachment; filename*=UTF-8''my%20secure%20backup.zip"
        val result = BrowserDownloadManager.parseContentDisposition(header)
        assertEquals("my secure backup.zip", result)
    }

    @Test
    fun parseContentDisposition_emptyOrNullReturnsNull() {
        assertNull(BrowserDownloadManager.parseContentDisposition(null))
        assertNull(BrowserDownloadManager.parseContentDisposition(""))
        assertNull(BrowserDownloadManager.parseContentDisposition("inline"))
    }

    @Test
    fun sanitizeFileName_removesDangerousCharacters() {
        val raw = "path/to/../my:cool*file?.apk"
        val sanitized = BrowserDownloadManager.sanitizeFileName(raw)
        assertEquals("path_to_.._my_cool_file_.apk", sanitized)
    }

    @Test
    fun guessExtension_mapsCommonMimeTypes() {
        assertEquals("pdf", BrowserDownloadManager.guessExtension("application/pdf"))
        assertEquals("png", BrowserDownloadManager.guessExtension("image/png"))
        assertEquals("jpg", BrowserDownloadManager.guessExtension("image/jpeg"))
        assertEquals("zip", BrowserDownloadManager.guessExtension("application/zip"))
        assertEquals("mp4", BrowserDownloadManager.guessExtension("video/mp4"))
    }
}
