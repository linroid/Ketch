package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.log.rememberLogFilesAction
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.downloads.actions.icon
import com.linroid.ketch.app.ui.downloads.actions.rowActionLabel
import com.linroid.ketch.app.ui.settings.LocalFileLogger
import com.linroid.ketch.app.util.ErrorCopy
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_copy_details
import ketch.app.shared.generated.resources.inspector_open_logs
import ketch.app.shared.generated.resources.inspector_open_logs_failed
import ketch.app.shared.generated.resources.inspector_share_logs
import ketch.app.shared.generated.resources.inspector_technical_details
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * What went wrong with a failed [row], from its [error] copy: the title, the hint, up to two of
 * its fixes that the action bar does not already offer ([inBar]), technical details when the
 * error has them, then Copy details and Open logs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProblemCard(
  row: TaskRow,
  error: ErrorCopy,
  runner: RowActionRunner,
  inBar: Set<RowAction>,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val failed = colors.status.failed
  val shape = KetchTheme.shapes.md
  val fixes = (listOf(error.primary) + error.secondary).distinct()
    .filter { it !in inBar && runner.commands.canRun(it, row) }
    .take(MAX_FIXES)
  Row(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier
      .fillMaxWidth()
      .background(failed.soft, shape)
      .border(spacing.s0_5 / 2, failed.color.copy(alpha = BORDER_ALPHA), shape)
      .padding(spacing.s3),
  ) {
    KetchIconImage(
      icon = KetchIcon.Failed,
      size = spacing.s4,
      tint = failed.color,
      modifier = Modifier.padding(top = spacing.s0_5),
    )
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.weight(1f),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
        Text(text = error.title.resolve(), style = type.titleM, color = colors.textPrimary)
        val hint = error.hint?.resolve()
        if (hint != null) Text(text = hint, style = type.bodyS, color = colors.textSecondary)
      }
      val details = error.details
      if (details != null) TechnicalDetails(details)
      if (fixes.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
          verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
          for (fix in fixes) {
            KetchButton(
              text = rowActionLabel(fix, runner.files?.revealLabel).resolve(),
              onClick = { runner.run(fix, listOf(row)) },
              variant = KetchButtonVariant.Secondary,
              size = KetchButtonSize.Small,
              leadingIcon = fix.icon,
            )
          }
        }
      }
      ProblemLinks(row, runner)
    }
  }
}

/** "Technical details", folded until clicked. */
@Composable
private fun TechnicalDetails(details: String) {
  var open by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
    Disclosure(stringResource(Res.string.inspector_technical_details), open) { open = !open }
    if (open) {
      Text(
        text = details,
        style = KetchTheme.typography.monoS,
        color = KetchTheme.colors.textSecondary,
      )
    }
  }
}

/** Copy details and, where the app keeps log files, Open logs, or Share logs on phones. */
@Composable
private fun ProblemLinks(row: TaskRow, runner: RowActionRunner) {
  val logger = LocalFileLogger.current
  val logs = logger?.let { rememberLogFilesAction(it) }
  val scope = rememberCoroutineScope()
  val state = runner.state
  val copy = runner.commands.canRun(RowAction.CopyDetails, row)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
  ) {
    if (copy) {
      TextLink(stringResource(Res.string.inspector_copy_details)) {
        runner.run(RowAction.CopyDetails, listOf(row))
      }
    }
    if (copy && logs != null) {
      Text(text = "·", style = KetchTheme.typography.labelS, color = KetchTheme.colors.textTertiary)
    }
    if (logs != null) {
      // Phones share a copy of the logs; elsewhere their folder opens.
      val label = if (isMobilePlatform) {
        Res.string.inspector_share_logs
      } else {
        Res.string.inspector_open_logs
      }
      TextLink(stringResource(label)) {
        scope.launch {
          catchingUnlessCancelled { logs.run() }.onFailure { e ->
            log.w { "Couldn't open the logs: ${e.describeCauses()}" }
            state.messages.post(
              level = MessageLevel.Error,
              title = Res.string.inspector_open_logs_failed.text(),
              cause = e,
            )
          }
        }
      }
    }
  }
}

private val log = KetchLogger("Inspector")

private const val MAX_FIXES = 2
private const val BORDER_ALPHA = 0.3f
