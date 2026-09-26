package com.linroid.ketch.app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FileKindTest {
  @Test
  fun of_extensionIgnoresCaseAndDirectories() {
    assertEquals(FileKind.Video, FileKind.of("Movie.MKV"))
    assertEquals(FileKind.Pdf, FileKind.of("docs/manual/Guide.pdf"))
    assertEquals(FileKind.Pdf, FileKind.of("C:\\Users\\me\\Report.PDF"))
  }

  @Test
  fun of_compoundExtension_usesLastExtension() {
    assertEquals(FileKind.Archive, FileKind.of("linux-6.9.tar.gz"))
    assertEquals(FileKind.Archive, FileKind.of("release.part1.rar"))
  }

  @Test
  fun of_torrentMetainfoFile_isTorrent() {
    assertEquals(FileKind.Torrent, FileKind.of("ubuntu-24.04-desktop-amd64.iso.torrent"))
  }

  @Test
  fun of_ambiguousExtensions_favorDownloadMeaning() {
    // MPEG transport streams and Keynote decks, not TypeScript and private keys.
    assertEquals(FileKind.Video, FileKind.of("segment-00042.ts"))
    assertEquals(FileKind.Presentation, FileKind.of("Keynote.key"))
  }

  @Test
  fun of_hiddenFile_usesNameAfterDot() {
    assertEquals(FileKind.Data, FileKind.of(".env"))
  }

  @Test
  fun of_noOrUnknownExtension_isUnknown() {
    assertEquals(FileKind.Unknown, FileKind.of("README"))
    assertEquals(FileKind.Unknown, FileKind.of(""))
    assertEquals(FileKind.Unknown, FileKind.of("notes.unknownext"))
    assertEquals(FileKind.Unknown, FileKind.of("Ubuntu 24.04.1 Desktop"))
  }

  @Test
  fun of_incompleteDownloadSuffix_classifiesUnderlyingFile() {
    assertEquals(FileKind.Video, FileKind.of("movie.mkv.part"))
    assertEquals(FileKind.App, FileKind.of("Setup.exe.crdownload"))
    assertEquals(FileKind.Unknown, FileKind.of("download"))
  }

  @Test
  fun of_splitVolume_isArchive() {
    assertEquals(FileKind.Archive, FileKind.of("movie.mkv.001"))
    assertEquals(FileKind.Archive, FileKind.of("backup.7z.002"))
    assertEquals(FileKind.Archive, FileKind.of("backup.r00"))
    assertEquals(FileKind.Archive, FileKind.of("backup.z01"))
  }

  @Test
  fun of_numberAfterUnknownPart_isNotSplitVolume() {
    assertEquals(FileKind.Unknown, FileKind.of("Show.2024.720"))
  }

  @Test
  fun of_torrentSourceWithoutKnownExtension_isTorrent() {
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
    assertEquals(FileKind.Torrent, FileKind.of("magnet:", sourceUrl = magnet))
    assertEquals(FileKind.Torrent, FileKind.of("Ubuntu 24.04", sourceUrl = "torrent:0123abcd"))
    assertEquals(
      FileKind.Torrent,
      FileKind.of("Some.Show.S01", sourceUrl = "https://example.org/show.torrent?key=1"),
    )
  }

  @Test
  fun of_torrentSourceWithKnownExtension_usesExtension() {
    val kind = FileKind.of("ubuntu.iso", sourceUrl = "magnet:?xt=urn:btih:abc")
    assertEquals(FileKind.DiskImage, kind)
  }

  @Test
  fun of_nonTorrentSourceWithoutExtension_isUnknown() {
    assertEquals(FileKind.Unknown, FileKind.of("file", sourceUrl = "https://example.org/file"))
  }

  @Test
  fun of_mimeType_classifiesNamesWithoutExtension() {
    assertEquals(FileKind.Video, FileKind.of("watch", mimeType = "video/mp4"))
    assertEquals(FileKind.Pdf, FileKind.of("view", mimeType = "Application/PDF; charset=binary"))
    assertEquals(FileKind.Image, FileKind.of("logo", mimeType = "image/svg+xml"))
    assertEquals(
      FileKind.Spreadsheet,
      FileKind.of(
        "export",
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      ),
    )
    val apk = FileKind.of("get", mimeType = "application/vnd.android.package-archive")
    assertEquals(FileKind.App, apk)
    assertEquals(FileKind.Unknown, FileKind.of("blob", mimeType = "application/octet-stream"))
  }

  @Test
  fun of_extension_winsOverMimeType() {
    assertEquals(FileKind.Archive, FileKind.of("bundle.zip", mimeType = "application/pdf"))
  }
}
