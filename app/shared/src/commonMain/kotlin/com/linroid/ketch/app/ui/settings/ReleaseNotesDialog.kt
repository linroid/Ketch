package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.shortDateText
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.platform.AppUpdateState
import com.linroid.ketch.app.platform.AppUpdates
import com.linroid.ketch.app.platform.LocalAppUpdates
import com.linroid.ketch.app.platform.ReleaseChange
import com.linroid.ketch.app.platform.ReleaseChangeKind
import com.linroid.ketch.app.platform.ReleaseNotes
import com.linroid.ketch.app.platform.ReleaseNotesRequest
import com.linroid.ketch.app.platform.withoutRepeats
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_close
import ketch.app.shared.generated.resources.action_try_again
import ketch.app.shared.generated.resources.settings_about_history
import ketch.app.shared.generated.resources.settings_about_history_changes
import ketch.app.shared.generated.resources.settings_about_history_count
import ketch.app.shared.generated.resources.settings_about_notes_failed
import ketch.app.shared.generated.resources.settings_about_notes_fixed
import ketch.app.shared.generated.resources.settings_about_notes_improved
import ketch.app.shared.generated.resources.settings_about_notes_loading
import ketch.app.shared.generated.resources.settings_about_notes_new
import ketch.app.shared.generated.resources.settings_about_notes_quiet
import ketch.app.shared.generated.resources.settings_about_notes_released
import ketch.app.shared.generated.resources.settings_about_notes_since
import ketch.app.shared.generated.resources.settings_about_update_install
import ketch.app.shared.generated.resources.settings_about_update_notes
import ketch.app.shared.generated.resources.settings_about_update_open_installer
import ketch.app.shared.generated.resources.settings_about_update_release_page
import ketch.app.shared.generated.resources.settings_about_update_restart
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val RELEASES_URL = "https://github.com/linroid/Ketch/releases"

private val log = KetchLogger("ReleaseNotes")

/**
 * The release notes the user asked to read outside Settings, such as from the toast of an
 * update, in a [ReleaseNotesDialog]. Only where the app updates itself, which reads them.
 */
@Composable
fun ReleaseNotesHost(state: AppState) {
  val request = state.releaseNotesRequest ?: return
  val updates = LocalAppUpdates.current ?: return
  ReleaseNotesDialog(updates, request, onDismiss = { state.releaseNotesRequest = null })
}

/**
 * What changed in the releases [request] names, newest first, as [updates] reads them: each
 * release's changes under New, Fixed and Improved. While [updates] offers the newest of them,
 * the main button takes the next step, downloading or installing it.
 */
@Composable
fun ReleaseNotesDialog(updates: AppUpdates, request: ReleaseNotesRequest, onDismiss: () -> Unit) {
  // Bumped by Try again, which reads the notes again.
  var attempt by remember { mutableIntStateOf(0) }
  var load by remember(request) { mutableStateOf<NotesLoad>(NotesLoad.Loading) }
  LaunchedEffect(updates, request, attempt) {
    load = NotesLoad.Loading
    load = try {
      NotesLoad.Loaded(updates.releaseNotes(request.version, request.since).withoutRepeats())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Couldn't read the notes of Ketch ${request.version}: ${e.describeCauses()}" }
      NotesLoad.Failed(e.message ?: e.describeCauses())
    }
  }
  val state by updates.state.collectAsState()
  val action = nextStep(state, request.version, updates)
  val close = @Composable {
    KetchButton(
      text = stringResource(Res.string.action_close),
      onClick = onDismiss,
      variant = KetchButtonVariant.Secondary,
    )
  }
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(Res.string.settings_about_update_notes, request.version)) },
    dismissButton = close.takeIf { action != null },
    confirmButton = {
      if (action == null) {
        close()
      } else {
        KetchButton(
          text = stringResource(action.label),
          onClick = {
            action.run()
            onDismiss()
          },
        )
      }
    },
  ) {
    val pageUrl = "$RELEASES_URL/tag/v${request.version}"
    when (val current = load) {
      NotesLoad.Loading -> NotesLoading()
      is NotesLoad.Failed -> NotesFailure(current.reason, pageUrl, onRetry = { attempt++ })
      is NotesLoad.Loaded -> {
        NotesList(current.notes, request.since)
        ReleasePageButton(current.notes.firstOrNull()?.pageUrl ?: pageUrl)
      }
    }
  }
}

/** The first release [ReleaseHistoryDialog] lists. */
internal const val FIRST_LISTED_RELEASE = "0.1.0"

/**
 * Every release from [FIRST_LISTED_RELEASE] up to [version], newest first, as [updates] reads
 * them: a line per release with its date and how many changes its notes list, which opens to
 * those changes under New, Fixed and Improved. The newest starts open.
 */
@Composable
fun ReleaseHistoryDialog(updates: AppUpdates, version: String, onDismiss: () -> Unit) {
  var attempt by remember { mutableIntStateOf(0) }
  var load by remember(version) { mutableStateOf<NotesLoad>(NotesLoad.Loading) }
  LaunchedEffect(updates, version, attempt) {
    load = NotesLoad.Loading
    load = try {
      // Each release keeps its notes as published, repeats included, so none drops out.
      NotesLoad.Loaded(updates.releaseHistory(version, FIRST_LISTED_RELEASE))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Couldn't read the releases up to Ketch $version: ${e.describeCauses()}" }
      NotesLoad.Failed(e.message ?: e.describeCauses())
    }
  }
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(Res.string.settings_about_history)) },
    confirmButton = {
      KetchButton(
        text = stringResource(Res.string.action_close),
        onClick = onDismiss,
        variant = KetchButtonVariant.Secondary,
      )
    },
  ) {
    when (val current = load) {
      NotesLoad.Loading -> NotesLoading()
      is NotesLoad.Failed -> NotesFailure(current.reason, RELEASES_URL, onRetry = { attempt++ })
      is NotesLoad.Loaded -> HistoryList(current.notes)
    }
  }
}

/** [releases], newest first, each a line that opens to its changes; the first starts open. */
@Composable
private fun HistoryList(releases: List<ReleaseNotes>) {
  val zone = remember { TimeZone.currentSystemDefault() }
  val today = LocalClock.current.now().toLocalDateTime(zone).date
  var open by remember(releases) { mutableStateOf(setOfNotNull(releases.firstOrNull()?.version)) }
  Text(
    text = pluralStringResource(
      Res.plurals.settings_about_history_count,
      releases.size,
      releases.size,
      FIRST_LISTED_RELEASE,
    ),
    style = KetchTheme.typography.bodyS,
    color = KetchTheme.colors.textSecondary,
  )
  Column {
    for ((index, release) in releases.withIndex()) {
      if (index > 0) {
        Box(Modifier.fillMaxWidth().height(HairlineWidth).background(KetchTheme.colors.divider))
      }
      val expanded = release.version in open
      HistoryEntry(release, expanded, today, zone, onToggle = {
        open = if (expanded) open - release.version else open + release.version
      })
    }
  }
}

/** The line of [release], which [onToggle] opens to its changes and its page, or closes. */
@Composable
private fun HistoryEntry(
  release: ReleaseNotes,
  expanded: Boolean,
  today: LocalDate,
  zone: TimeZone,
  onToggle: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .clip(KetchTheme.shapes.sm)
        .clickable(role = Role.Button, onClick = onToggle)
        .padding(vertical = spacing.s2),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = release.version,
          style = KetchTheme.typography.bodyStrong,
          color = colors.textPrimary,
        )
        release.publishedAt?.let { published ->
          Text(
            text = shortDateText(published.toLocalDateTime(zone).date, today).resolve(),
            style = KetchTheme.typography.caption,
            color = colors.textTertiary,
          )
        }
      }
      if (release.changes.isNotEmpty()) {
        Text(
          text = pluralStringResource(
            Res.plurals.settings_about_history_changes,
            release.changes.size,
            release.changes.size,
          ),
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
        )
      }
      KetchIconImage(
        icon = if (expanded) KetchIcon.ChevronUp else KetchIcon.ChevronDown,
        size = KetchTheme.density.controlGlyph,
        tint = colors.textTertiary,
      )
    }
    if (expanded) {
      Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s3),
        modifier = Modifier.padding(bottom = spacing.s2),
      ) {
        ReleaseChanges(release.changes)
        ReleasePageButton(release.pageUrl)
      }
    }
  }
}

/** A spinner beside the line saying the notes are loading. */
@Composable
private fun NotesLoading() {
  Row(
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    KetchSpinner()
    Text(
      text = stringResource(Res.string.settings_about_notes_loading),
      style = KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textSecondary,
    )
  }
}

/** Where reading the notes of a [ReleaseNotesDialog] stands. */
private sealed interface NotesLoad {
  data object Loading : NotesLoad

  data class Loaded(val notes: List<ReleaseNotes>) : NotesLoad

  /** The notes could not be read, for [reason], in English as the updater reports it. */
  data class Failed(val reason: String) : NotesLoad
}

/** A step of the update that the dialog's main button takes: what it says and does. */
private class NextStep(val label: StringResource, val run: () -> Unit)

/** The step [state] offers for [version]; `null` when it offers none, as for another version. */
private fun nextStep(state: AppUpdateState, version: String, updates: AppUpdates): NextStep? =
  when {
    state is AppUpdateState.Available && state.version == version && state.installable ->
      NextStep(Res.string.settings_about_update_install, updates::download)
    state is AppUpdateState.Ready && state.version == version -> NextStep(
      label = if (state.restarts) {
        Res.string.settings_about_update_restart
      } else {
        Res.string.settings_about_update_open_installer
      },
      run = updates::install,
    )
    else -> null
  }

/**
 * The releases of [notes], newest first. One release says when it came out; several, read since
 * [since], say how many, and each starts with its version.
 */
@Composable
private fun NotesList(notes: List<ReleaseNotes>, since: String?) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val zone = remember { TimeZone.currentSystemDefault() }
  val today = LocalClock.current.now().toLocalDateTime(zone).date
  val single = notes.singleOrNull()
  val subtitle = when {
    single != null -> single.publishedAt?.let { published ->
      val date = shortDateText(published.toLocalDateTime(zone).date, today).resolve()
      stringResource(Res.string.settings_about_notes_released, date)
    }
    since != null -> pluralStringResource(
      Res.plurals.settings_about_notes_since,
      notes.size,
      notes.size,
      since,
    )
    else -> null
  }
  if (subtitle != null) {
    Text(text = subtitle, style = KetchTheme.typography.bodyS, color = colors.textSecondary)
  }
  for (release in notes) {
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier.padding(top = if (single == null) spacing.s2 else 0.dp),
    ) {
      if (single == null) ReleaseHeading(release, today, zone)
      ReleaseChanges(release.changes)
    }
  }
}

/** The version of [release] and the day it came out. */
@Composable
private fun ReleaseHeading(release: ReleaseNotes, today: LocalDate, zone: TimeZone) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
    verticalAlignment = Alignment.Bottom,
  ) {
    Text(
      text = release.version,
      style = KetchTheme.typography.titleM,
      color = KetchTheme.colors.textPrimary,
    )
    release.publishedAt?.let { published ->
      Text(
        text = shortDateText(published.toLocalDateTime(zone).date, today).resolve(),
        style = KetchTheme.typography.caption,
        color = KetchTheme.colors.textTertiary,
      )
    }
  }
}

/** [changes] under a heading per kind; a line saying there is nothing to notice without any. */
@Composable
private fun ReleaseChanges(changes: List<ReleaseChange>) {
  if (changes.isEmpty()) {
    Text(
      text = stringResource(Res.string.settings_about_notes_quiet),
      style = KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textSecondary,
    )
    return
  }
  for (kind in ReleaseChangeKind.entries) {
    val ofKind = changes.filter { it.kind == kind }
    if (ofKind.isEmpty()) continue
    Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
      KetchEyebrow(stringResource(kind.heading))
      val dot = kind.dotColor()
      for (change in ofKind) ChangeLine(change, dot)
    }
  }
}

/** One change: a dot of [dot] color, its summary and, quieter, the part of Ketch it touches. */
@Composable
private fun ChangeLine(change: ReleaseChange, dot: Color) {
  val colors = KetchTheme.colors
  val style = KetchTheme.typography.body
  // The dot sits in the middle of the first line.
  val dotTop = with(LocalDensity.current) { (style.lineHeight.toDp() - DotSize) / 2 }
  Row(horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2)) {
    Box(
      Modifier
        .padding(top = dotTop.coerceAtLeast(0.dp))
        .size(DotSize)
        .background(dot, CircleShape),
    )
    Text(
      text = buildAnnotatedString {
        append(change.summary)
        change.area?.let { area ->
          withStyle(SpanStyle(color = colors.textTertiary)) { append(SEPARATOR + area) }
        }
      },
      style = style,
      color = colors.textPrimary,
    )
  }
}

/** Why the notes could not be read, with Try again and the release page, which has them too. */
@Composable
private fun NotesFailure(reason: String, pageUrl: String, onRetry: () -> Unit) {
  val colors = KetchTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
    Text(
      text = stringResource(Res.string.settings_about_notes_failed),
      style = KetchTheme.typography.bodyStrong,
      color = colors.status.failed.color,
    )
    Text(text = reason, style = KetchTheme.typography.bodyS, color = colors.textSecondary)
  }
  Row(horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2)) {
    KetchButton(
      text = stringResource(Res.string.action_try_again),
      onClick = onRetry,
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
    )
    ReleasePageButton(pageUrl)
  }
}

/** Opens the release's web page, with the full notes and the downloads. */
@Composable
private fun ReleasePageButton(url: String) {
  val uriHandler = LocalUriHandler.current
  KetchButton(
    text = stringResource(Res.string.settings_about_update_release_page),
    onClick = { openUri(uriHandler, url) },
    variant = KetchButtonVariant.Ghost,
    size = KetchButtonSize.Small,
    leadingIcon = KetchIcon.Open,
  )
}

private val ReleaseChangeKind.heading: StringResource
  get() = when (this) {
    ReleaseChangeKind.Feature -> Res.string.settings_about_notes_new
    ReleaseChangeKind.Fix -> Res.string.settings_about_notes_fixed
    ReleaseChangeKind.Improvement -> Res.string.settings_about_notes_improved
  }

@Composable
private fun ReleaseChangeKind.dotColor(): Color = when (this) {
  ReleaseChangeKind.Feature -> KetchTheme.colors.accent
  ReleaseChangeKind.Fix -> KetchTheme.colors.status.completed.color
  ReleaseChangeKind.Improvement -> KetchTheme.colors.textTertiary
}

private val DotSize = 6.dp
