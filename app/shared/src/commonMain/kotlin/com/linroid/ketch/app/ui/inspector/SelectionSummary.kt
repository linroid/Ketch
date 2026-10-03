package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.LaneStrip
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_close
import ketch.app.shared.generated.resources.inspector_controls_apply_to
import ketch.app.shared.generated.resources.inspector_selection_more
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the inspector shows for several selected [rows]: "3 selected · 2.4 GB · 9.1 MB/s", a
 * lane strip per download, and the Controls they share, which set them all.
 */
@Composable
internal fun SelectionSummary(
  state: AppState,
  rows: List<TaskRow>,
  runner: RowActionRunner,
  pending: Set<Pair<TaskKey, String>>,
  onClose: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
      Text(
        text = selectionLine(rows).resolve(),
        style = type.titleM,
        color = colors.textPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = stringResource(Res.string.inspector_close),
        onClick = onClose,
        size = KetchButtonSize.Small,
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
      for (row in rows.take(MAX_MAPS)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
          modifier = Modifier.fillMaxWidth(),
        ) {
          MiddleText(
            text = row.name,
            style = type.caption,
            color = colors.textSecondary,
            modifier = Modifier.weight(NAME_SHARE),
          )
          key(row.key) {
            LaneStrip(
              state = row.state,
              segments = row.segments,
              modifier = Modifier.weight(1f - NAME_SHARE),
            )
          }
        }
      }
      val more = rows.size - MAX_MAPS
      if (more > 0) {
        Text(
          text = pluralStringResource(Res.plurals.inspector_selection_more, more, more),
          style = type.caption,
          color = colors.textTertiary,
        )
      }
    }
    val controllable = rows.filter { it.state.hasControls }
    if (controllable.isNotEmpty()) {
      InspectorControls(state, controllable, runner, pending)
      if (controllable.size < rows.size) {
        Text(
          text = pluralStringResource(
            Res.plurals.inspector_controls_apply_to,
            rows.size,
            controllable.size,
            rows.size,
          ),
          style = type.caption,
          color = colors.textTertiary,
        )
      }
    }
  }
}

private const val MAX_MAPS = 8
private const val NAME_SHARE = 0.42f
