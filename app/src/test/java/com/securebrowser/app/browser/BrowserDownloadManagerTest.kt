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
        assertEquals("apk", BrowserDownloadManager.guessExtension("application/vnd.android.package-archive"))
    }

    @Test
    fun isNewerVersion_comparesSemanticVersionsCorrectly() {
        org.junit.Assert.assertTrue(com.securebrowser.app.update.AppUpdateManager.isNewerVersion("v1.12.0", "1.11.0"))
        org.junit.Assert.assertTrue(com.securebrowser.app.update.AppUpdateManager.isNewerVersion("1.12.1", "1.12.0"))
        org.junit.Assert.assertTrue(com.securebrowser.app.update.AppUpdateManager.isNewerVersion("2.0.0", "1.99.9"))
        org.junit.Assert.assertFalse(com.securebrowser.app.update.AppUpdateManager.isNewerVersion("1.12.0", "1.12.0"))
        org.junit.Assert.assertFalse(com.securebrowser.app.update.AppUpdateManager.isNewerVersion("1.11.0", "1.12.0"))
    }

    @Test
    fun isSearchEngineUrl_detectsGoogleBingDuckDuckGoWhilePreservingClassroom() {
        fun parse(raw: String) =
            (com.securebrowser.app.core.url.UrlNormalizer.normalize(raw) as com.securebrowser.app.core.url.NormalizeResult.Success).url

        org.junit.Assert.assertTrue(com.securebrowser.app.security.SecurityEngine.isSearchEngineUrl(parse("https://www.google.com/search?q=test")))
        org.junit.Assert.assertTrue(com.securebrowser.app.security.SecurityEngine.isSearchEngineUrl(parse("https://images.google.com/")))
        org.junit.Assert.assertTrue(com.securebrowser.app.security.SecurityEngine.isSearchEngineUrl(parse("https://www.bing.com/images")))
        org.junit.Assert.assertTrue(com.securebrowser.app.security.SecurityEngine.isSearchEngineUrl(parse("https://duckduckgo.com/?q=hello")))
        org.junit.Assert.assertFalse(com.securebrowser.app.security.SecurityEngine.isSearchEngineUrl(parse("https://classroom.google.com/u/0/h")))
        org.junit.Assert.assertFalse(com.securebrowser.app.security.SecurityEngine.isSearchEngineUrl(parse("https://drive.google.com/drive/my-drive")))
    }
}
