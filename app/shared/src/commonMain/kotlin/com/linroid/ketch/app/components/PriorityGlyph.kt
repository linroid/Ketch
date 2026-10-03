package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.component_priority_high
import ketch.app.shared.generated.resources.component_priority_low
import ketch.app.shared.generated.resources.component_priority_urgent
import org.jetbrains.compose.resources.stringResource

/**
 * Marks a task's [priority] without borrowing a status color: a tertiary chevron down for Low,
 * nothing for Normal, an accent chevron up for High, and a bolt on an accent pill for Urgent.
 */
@Composable
fun PriorityGlyph(
  priority: DownloadPriority,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val description = when (priority) {
    DownloadPriority.NORMAL -> return
    DownloadPriority.LOW -> stringResource(Res.string.component_priority_low)
    DownloadPriority.HIGH -> stringResource(Res.string.component_priority_high)
    DownloadPriority.URGENT -> stringResource(Res.string.component_priority_urgent)
  }
  when (priority) {
    DownloadPriority.NORMAL -> Unit
    DownloadPriority.LOW, DownloadPriority.HIGH -> KetchIconImage(
      icon = if (priority == DownloadPriority.LOW) KetchIcon.ChevronDown else KetchIcon.ChevronUp,
      size = ChevronSize,
      tint = if (priority == DownloadPriority.LOW) colors.textTertiary else colors.accentText,
      modifier = modifier.semantics { contentDescription = description },
    )
    DownloadPriority.URGENT -> Box(
      contentAlignment = Alignment.Center,
      modifier = modifier
        .semantics { contentDescription = description }
        .height(PillHeight)
        .background(colors.accent, KetchTheme.shapes.full)
        .padding(horizontal = KetchTheme.spacing.s1),
    ) {
      KetchIconImage(KetchIcon.Bolt, size = BoltSize, tint = colors.onAccent)
    }
  }
}

private val ChevronSize = 16.dp
private val PillHeight = 16.dp
private val BoltSize = 12.dp
