package com.linroid.ketch.app.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** Sizes of a [KetchFileTypeChip] in each place it appears. */
object KetchFileTypeChipDefaults {
  /** Table rows. */
  val TableSize: Dp = 20.dp

  /** List rows with a pointer. */
  val ListSize: Dp = 28.dp

  /** List rows on touch. */
  val TouchSize: Dp = 36.dp

  /** The inspector header and the add sheet's preview. */
  val LargeSize: Dp = 40.dp
}

/**
 * File-type tile rendered to the left of a file name: the glyph of the file's [FileKind] on a
 * tint of its hue, so kinds stay distinguishable by shape as well as color.
 *
 * [sourceUrl] and [mimeType] classify names without a known extension; see [FileKind.of].
 *
 * @param size one of the [KetchFileTypeChipDefaults] sizes.
 * @param showCheck cross-fades the glyph to a check in the completed color, as a row does for a
 *   moment after its download completes.
 */
@Composable
fun KetchFileTypeChip(
  fileName: String,
  modifier: Modifier = Modifier,
  sourceUrl: String? = null,
  mimeType: String? = null,
  size: Dp = KetchFileTypeChipDefaults.TouchSize,
  showCheck: Boolean = false,
) {
  val kind = remember(fileName, sourceUrl, mimeType) { FileKind.of(fileName, sourceUrl, mimeType) }
  val colors = KetchTheme.colors
  val motion = KetchTheme.motion
  val hueColor = kind.hue?.let { if (colors.isDark) it.dark else it.light } ?: colors.textSecondary
  val tint by animateColorAsState(
    targetValue = if (showCheck) colors.status.completed.color else hueColor,
    animationSpec = tween(motion.medium),
  )
  val shape = rememberTileShape(size)
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .clip(shape)
      .background(tint.copy(alpha = FileTypeHue.TILE_ALPHA)),
  ) {
    Crossfade(targetState = showCheck, animationSpec = tween(motion.medium)) { check ->
      KetchIconImage(
        icon = if (check) KetchIcon.Check else kind.icon,
        size = size * GLYPH_SHARE,
        tint = tint,
      )
    }
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

private const val GLYPH_SHARE = 0.56f
