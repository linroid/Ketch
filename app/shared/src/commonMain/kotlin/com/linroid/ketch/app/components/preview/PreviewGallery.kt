package com.linroid.ketch.app.components.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode

/** The page of a preview gallery, in one theme and density, with motion reduced. */
@Composable
internal fun PreviewGallery(
  darkTheme: Boolean,
  density: DensityMode,
  content: @Composable ColumnScope.() -> Unit,
) {
  KetchTheme(darkTheme = darkTheme, density = density, reduceMotion = true) {
    val spacing = KetchTheme.spacing
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s4),
      modifier = Modifier
        .width(GalleryWidth)
        .background(KetchTheme.colors.surface)
        .padding(spacing.s6),
      content = content,
    )
  }
}

/** A titled group of a gallery; its items wrap in rows, or with [fill] stack at full width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreviewSection(title: String, fill: Boolean = false, content: @Composable () -> Unit) {
  val spacing = KetchTheme.spacing
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    KetchEyebrow(title)
    if (fill) {
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) { content() }
    } else {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
        verticalArrangement = Arrangement.spacedBy(spacing.s3),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) { content() }
    }
  }
}

private val GalleryWidth = 720.dp
