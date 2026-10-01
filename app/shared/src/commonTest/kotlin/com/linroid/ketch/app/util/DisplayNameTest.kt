package com.linroid.ketch.app.util

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayNameTest {
  @Test
  fun displayName_completed_usesOutputFileName() {
    val request = DownloadRequest(
      url = "https://example.com/file.zip",
      destination = Destination("custom.zip"),
    )
    val state = DownloadState.Completed("/home/me/Downloads/file (1).zip")

    assertEquals("file (1).zip", displayName(request, state))
  }

  @Test
  fun displayName_completedWindowsPath_usesOutputFileName() {
    val request = DownloadRequest("https://example.com/file.zip")
    val state = DownloadState.Completed("C:\\Users\\me\\Downloads\\report.pdf")

    assertEquals("report.pdf", displayName(request, state))
  }

  @Test
  fun displayName_completedContentUri_usesDocumentFileName() {
    val request = DownloadRequest("https://example.com/download?id=1")
    val outputPath = "content://com.android.externalstorage.documents/tree/primary%3ADownload" +
      "/document/primary%3ADownload%2Fubuntu.iso"

    assertEquals("ubuntu.iso", displayName(request, DownloadState.Completed(outputPath)))
  }

  @Test
  fun displayName_nameDestination_usesName() {
    val request = DownloadRequest("https://example.com/a", destination = Destination("b.iso"))

    assertEquals("b.iso", displayName(request, DownloadState.Queued))
  }

  @Test
  fun displayName_fileDestination_usesBaseName() {
    val request = DownloadRequest(
      url = "https://example.com/a",
      destination = Destination("/tmp/downloads/b.iso"),
    )

    assertEquals("b.iso", displayName(request, DownloadState.Queued))
  }

  @Test
  fun displayName_directoryDestinations_areSkipped() {
    for (directory in listOf("dir/", "./", ".", "..", "/tmp/downloads/", "C:\\Downloads\\")) {
      val request = DownloadRequest(
        url = "https://example.com/files/a.iso",
        destination = Destination(directory),
      )

      assertEquals("a.iso", displayName(request), "destination $directory")
    }
  }

  @Test
  fun displayName_contentTreeDestination_isSkipped() {
    val request = DownloadRequest(
      url = "https://example.com/files/a.iso",
      destination = Destination(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload",
      ),
    )

    assertEquals("a.iso", displayName(request))
  }

  @Test
  fun displayName_suggestedFileName_winsOverUrl() {
    val request = DownloadRequest(
      url = "https://example.com/download?id=1",
      resolvedSource = ResolvedSource(
        url = "https://example.com/download?id=1",
        sourceType = "http",
        totalBytes = 10,
        supportsResume = true,
        suggestedFileName = "report.pdf",
        maxSegments = 1,
      ),
    )

    assertEquals("report.pdf", displayName(request))
  }

  @Test
  fun displayName_magnetWithDn_usesDecodedDn() {
    val request = DownloadRequest("magnet:?xt=urn:btih:$HEX_HASH&dn=Big+Buck%20Bunny%E2%9C%93")

    assertEquals("Big Buck Bunny✓", displayName(request))
  }

  @Test
  fun displayName_magnetWithoutDn_usesInfoHash() {
    val request = DownloadRequest("magnet:?xt=urn:btih:${HEX_HASH.uppercase()}&tr=udp%3A%2F%2Ft")

    assertEquals("Magnet c12fe1c0", displayName(request))
  }

  @Test
  fun displayName_magnetBase32Hash_usesHexDigits() {
    val request = DownloadRequest("magnet:?xt=urn:btih:YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK")

    assertEquals("Magnet c12fe1c0", displayName(request))
  }

  @Test
  fun displayName_magnetV2Only_usesMultihashDigest() {
    val digest = "0a1b2c3d" + "0".repeat(56)
    val request = DownloadRequest("magnet:?xt=urn:btmh:1220$digest")

    assertEquals("Magnet 0a1b2c3d", displayName(request))
  }

  @Test
  fun displayName_torrentId_usesHash() {
    assertEquals("Torrent c12fe1c0", displayName(DownloadRequest("torrent:$HEX_HASH")))
  }

  @Test
  fun displayName_percentEncodedUrl_decodesBaseName() {
    val request = DownloadRequest("https://example.com/files/my%20file%2B1.zip?token=x#top")

    assertEquals("my file+1.zip", displayName(request))
  }

  @Test
  fun displayName_dotAndEmptySegments_areIgnored() {
    val request = DownloadRequest("https://example.com/files/a.iso/./..//")

    assertEquals("a.iso", displayName(request))
  }

  @Test
  fun displayName_urlWithoutPath_usesHost() {
    assertEquals("example.com", displayName(DownloadRequest("https://Example.COM:8443/")))
  }

  @Test
  fun urlHost_stripsUserInfoPortAndBrackets() {
    assertEquals("ftp.example.com", urlHost("ftp://user:p%40ss@FTP.example.com:21/a"))
    assertEquals("::1", urlHost("http://[::1]:8080/a"))
  }

  @Test
  fun urlHost_hostlessLinks_areNull() {
    assertNull(urlHost("magnet:?xt=urn:btih:$HEX_HASH"))
    assertNull(urlHost("torrent:$HEX_HASH"))
    assertNull(urlHost("file:///tmp/a.iso"))
    assertNull(urlHost("/tmp/a.iso"))
  }

  @Test
  fun percentDecode_malformedEscapes_areKept() {
    assertEquals("100% %zz %4", percentDecode("100% %zz %4"))
  }

  @Test
  fun percentDecode_plus_isSpaceOnlyWhenAsked() {
    assertEquals("a+b", percentDecode("a+b"))
    assertEquals("a b", percentDecode("a+b", plusAsSpace = true))
  }

  private companion object {
    const val HEX_HASH = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
  }
}
