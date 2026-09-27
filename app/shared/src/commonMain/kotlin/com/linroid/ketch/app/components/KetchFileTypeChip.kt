package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.FileTypeHue
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.FileKind

/**
 * File-type tile rendered to the left of a file name: the glyph of the file's [FileKind] on a
 * tint of its hue, so kinds stay distinguishable by shape as well as color.
 *
 * [sourceUrl] and [mimeType] classify names without a known extension; see [FileKind.of].
 */
@Composable
fun KetchFileTypeChip(
  fileName: String,
  modifier: Modifier = Modifier,
  sourceUrl: String? = null,
  mimeType: String? = null,
  size: Dp = 36.dp,
) {
  val kind = remember(fileName, sourceUrl, mimeType) { FileKind.of(fileName, sourceUrl, mimeType) }
  val colors = KetchTheme.colors
  val color = kind.hue?.let { if (colors.isDark) it.dark else it.light }
    ?: colors.onSurfaceVariant

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(size * 0.28f))
      .background(color.copy(alpha = FileTypeHue.TILE_ALPHA)),
  ) {
    KetchIconImage(icon = kind.icon, size = size * 0.56f, tint = color)
  }
}

private val FileKind.icon: KetchIcon
  get() = when (this) {
    FileKind.Torrent -> KetchIcon.FileTorrent
    FileKind.Video -> KetchIcon.FileVideo
    FileKind.Audio -> KetchIcon.FileAudio
    FileKind.Subtitle -> KetchIcon.FileSubtitle
    FileKind.Image -> KetchIcon.FileImage
    FileKind.Design -> KetchIcon.FileDesign
    FileKind.Document -> KetchIcon.FileDocument
    FileKind.Pdf -> KetchIcon.FilePdf
    FileKind.Ebook -> KetchIcon.FileEbook
    FileKind.Spreadsheet -> KetchIcon.FileSpreadsheet
    FileKind.Presentation -> KetchIcon.FilePresentation
    FileKind.Text -> KetchIcon.FileText
    FileKind.Code -> KetchIcon.FileCode
    FileKind.Data -> KetchIcon.FileData
    FileKind.Database -> KetchIcon.FileDatabase
    FileKind.Web -> KetchIcon.FileWeb
    FileKind.Archive -> KetchIcon.FileArchive
    FileKind.DiskImage -> KetchIcon.FileDiskImage
    FileKind.App -> KetchIcon.FileApp
    FileKind.Model3d -> KetchIcon.FileModel3d
    FileKind.Font -> KetchIcon.FileFont
    FileKind.Key -> KetchIcon.FileKey
    FileKind.Email -> KetchIcon.FileEmail
    FileKind.Unknown -> KetchIcon.FileGeneric
  }

/** Related kinds share a hue (video and subtitles, code and web); glyphs tell them apart. */
private val FileKind.hue: FileTypeHue?
  get() = when (this) {
    FileKind.Torrent -> FileTypeHue.Jade
    FileKind.Video, FileKind.Subtitle -> FileTypeHue.Violet
    FileKind.Audio, FileKind.Design -> FileTypeHue.Magenta
    FileKind.Image -> FileTypeHue.Teal
    FileKind.Document, FileKind.Email -> FileTypeHue.Blue
    FileKind.Pdf -> FileTypeHue.Red
    FileKind.Ebook, FileKind.Font -> FileTypeHue.Brown
    FileKind.Spreadsheet -> FileTypeHue.Green
    FileKind.Presentation, FileKind.Model3d -> FileTypeHue.Orange
    FileKind.Text, FileKind.DiskImage -> FileTypeHue.Slate
    FileKind.Code, FileKind.Web -> FileTypeHue.Sky
    FileKind.Data -> FileTypeHue.Lime
    FileKind.Database, FileKind.App -> FileTypeHue.Indigo
    FileKind.Archive, FileKind.Key -> FileTypeHue.Amber
    FileKind.Unknown -> null
  }
