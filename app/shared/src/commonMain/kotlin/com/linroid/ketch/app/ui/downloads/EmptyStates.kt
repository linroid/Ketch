package com.linroid.ketch.app.ui.downloads

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.links
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.downloads_empty_add
import ketch.app.shared.generated.resources.downloads_empty_add_it_to
import ketch.app.shared.generated.resources.downloads_empty_add_link
import ketch.app.shared.generated.resources.downloads_empty_add_links
import ketch.app.shared.generated.resources.downloads_empty_add_them_to
import ketch.app.shared.generated.resources.downloads_empty_add_to
import ketch.app.shared.generated.resources.downloads_empty_all
import ketch.app.shared.generated.resources.downloads_empty_clear_search
import ketch.app.shared.generated.resources.downloads_empty_done
import ketch.app.shared.generated.resources.downloads_empty_done_hint
import ketch.app.shared.generated.resources.downloads_empty_downloading
import ketch.app.shared.generated.resources.downloads_empty_downloading_hint
import ketch.app.shared.generated.resources.downloads_empty_failed
import ketch.app.shared.generated.resources.downloads_empty_failed_hint
import ketch.app.shared.generated.resources.downloads_empty_fleet
import ketch.app.shared.generated.resources.downloads_empty_fleet_hint
import ketch.app.shared.generated.resources.downloads_empty_link_missing
import ketch.app.shared.generated.resources.downloads_empty_links_missing
import ketch.app.shared.generated.resources.downloads_empty_loading
import ketch.app.shared.generated.resources.downloads_empty_no_match
import ketch.app.shared.generated.resources.downloads_empty_no_match_hint
import ketch.app.shared.generated.resources.downloads_empty_offline
import ketch.app.shared.generated.resources.downloads_empty_offline_hint
import ketch.app.shared.generated.resources.downloads_empty_paused
import ketch.app.shared.generated.resources.downloads_empty_paused_hint
import ketch.app.shared.generated.resources.downloads_empty_remote
import ketch.app.shared.generated.resources.downloads_empty_show_all
import ketch.app.shared.generated.resources.downloads_empty_unauthorized
import ketch.app.shared.generated.resources.downloads_empty_waiting
import ketch.app.shared.generated.resources.downloads_empty_waiting_hint
import ketch.app.shared.generated.resources.downloads_empty_waiting_hint_one
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What an empty tab or search says, and the way out of it.
 *
 * @property title the headline, such as "Nothing needs attention".
 * @property hint a line under it, or `null`.
 * @property icon the glyph above it.
 * @property action what the button does, or `null` for no button.
 * @property actionLabel the button's label.
 */
internal data class EmptyCopy(
  val title: UiText,
  val hint: UiText? = null,
  val icon: KetchIcon,
  val action: EmptyAction? = null,
  val actionLabel: UiText? = action?.label,
)

/** What the button of an empty tab or search does. */
internal enum class EmptyAction(private val resource: StringResource) {
  ClearSearch(Res.string.downloads_empty_clear_search),
  AddLinks(Res.string.downloads_empty_add_link),
  ShowAll(Res.string.downloads_empty_show_all),

  /** Opens the add sheet. */
  Add(Res.string.downloads_empty_add);

  /** The button's label, such as "Clear search". */
  val label: UiText
    get() = resource.text()
}

/**
 * The copy of a tab that shows nothing: the [filter] tab is empty, or nothing on it matches
 * [query].
 *
 * @param deviceName the device the list shows, such as "This Mac".
 * @param slots how many downloads the device runs at a time, or `null` while unknown.
 */
internal fun emptyCopy(
  filter: StatusFilter,
  query: String,
  deviceName: UiText,
  slots: Int?,
): EmptyCopy {
  val search = query.trim()
  if (search.isNotEmpty()) {
    val links = LinkParser.parseIntake(search).links()
    return if (links.isNotEmpty()) {
      val one = links.size == 1
      EmptyCopy(
        title = if (one) {
          Res.string.downloads_empty_link_missing.text()
        } else {
          Res.string.downloads_empty_links_missing.text()
        },
        hint = if (one) {
          Res.string.downloads_empty_add_it_to.text(deviceName)
        } else {
          Res.string.downloads_empty_add_them_to.text(deviceName)
        },
        icon = KetchIcon.Link,
        action = EmptyAction.AddLinks,
        actionLabel = if (one) {
          EmptyAction.AddLinks.label
        } else {
          Res.string.downloads_empty_add_links.text()
        },
      )
    } else {
      EmptyCopy(
        title = Res.string.downloads_empty_no_match.text(search),
        hint = Res.string.downloads_empty_no_match_hint.text(),
        icon = KetchIcon.Search,
        action = EmptyAction.ClearSearch,
      )
    }
  }
  return when (filter) {
    StatusFilter.All -> EmptyCopy(
      title = Res.string.downloads_empty_all.text(),
      icon = KetchIcon.Active,
    )
    StatusFilter.Downloading -> EmptyCopy(
      title = Res.string.downloads_empty_downloading.text(),
      hint = Res.string.downloads_empty_downloading_hint.text(),
      icon = KetchIcon.Active,
      action = EmptyAction.ShowAll,
    )
    StatusFilter.Waiting -> EmptyCopy(
      title = Res.string.downloads_empty_waiting.text(),
      hint = slots?.let { runsAtATime(deviceName, it) },
      icon = KetchIcon.Queued,
      action = EmptyAction.ShowAll,
    )
    StatusFilter.Paused -> EmptyCopy(
      title = Res.string.downloads_empty_paused.text(),
      hint = Res.string.downloads_empty_paused_hint.text(),
      icon = KetchIcon.Pause,
      action = EmptyAction.ShowAll,
    )
    StatusFilter.Done -> EmptyCopy(
      title = Res.string.downloads_empty_done.text(),
      hint = Res.string.downloads_empty_done_hint.text(),
      icon = KetchIcon.Done,
      action = EmptyAction.ShowAll,
    )
    StatusFilter.Failed -> EmptyCopy(
      title = Res.string.downloads_empty_failed.text(),
      hint = Res.string.downloads_empty_failed_hint.text(),
      icon = KetchIcon.CheckCircle,
      action = EmptyAction.ShowAll,
    )
  }
}

/**
 * The copy of every device shown at once while none has downloads, with a button that adds one
 * to [targetName], where new downloads go.
 */
internal fun fleetEmptyCopy(targetName: UiText): EmptyCopy = EmptyCopy(
  title = Res.string.downloads_empty_fleet.text(),
  hint = Res.string.downloads_empty_fleet_hint.text(),
  icon = KetchIcon.Fleet,
  action = EmptyAction.Add,
  actionLabel = Res.string.downloads_empty_add_to.text(targetName),
)

/** The copy of a remote device that has no downloads yet, with a button that adds one to it. */
internal fun remoteEmptyCopy(deviceName: UiText): EmptyCopy = EmptyCopy(
  title = Res.string.downloads_empty_remote.text(deviceName),
  icon = KetchIcon.Server,
  action = EmptyAction.Add,
  actionLabel = Res.string.downloads_empty_add_to.text(deviceName),
)

/**
 * The copy of a remote device that cannot be reached and has sent no downloads yet: offline, or
 * refusing its access token when [unauthorized]. The banner above the page offers the way out.
 */
internal fun offlineCopy(deviceName: UiText, unauthorized: Boolean): EmptyCopy = EmptyCopy(
  title = if (unauthorized) {
    Res.string.downloads_empty_unauthorized.text(deviceName)
  } else {
    Res.string.downloads_empty_offline.text(deviceName)
  },
  hint = Res.string.downloads_empty_offline_hint.text(),
  icon = KetchIcon.Server,
)

/** "This Mac runs 3 at a time.", or "This Mac runs one download at a time." for one [slots]. */
private fun runsAtATime(deviceName: UiText, slots: Int): UiText = if (slots == 1) {
  Res.string.downloads_empty_waiting_hint_one.text(deviceName)
} else {
  Res.plurals.downloads_empty_waiting_hint.text(slots, deviceName, slots)
}

/** An empty tab or search, centered, with [copy]'s button running [onAction]. */
@Composable
internal fun EmptyMessage(
  copy: EmptyCopy,
  onAction: (EmptyAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val happy = copy.icon == KetchIcon.CheckCircle
  Box(modifier.fillMaxSize().padding(spacing.s6), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.widthIn(max = MessageWidth),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .size(spacing.s12)
          .background(
            if (happy) colors.status.completed.soft else colors.surfaceSunken,
            KetchTheme.shapes.full
          ),
      ) {
        KetchIconImage(
          icon = copy.icon,
          size = spacing.s6,
          tint = if (happy) colors.status.completed.color else colors.textTertiary,
        )
      }
      Spacer(Modifier.height(spacing.s1))
      Text(
        text = copy.title.resolve(),
        style = KetchTheme.typography.titleM,
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
      )
      copy.hint?.let {
        Text(
          text = it.resolve(),
          style = KetchTheme.typography.bodyS,
          color = colors.textSecondary,
          textAlign = TextAlign.Center,
        )
      }
      val action = copy.action
      if (action != null && copy.actionLabel != null) {
        Spacer(Modifier.height(spacing.s1))
        KetchButton(
          text = copy.actionLabel.resolve(),
          onClick = { onAction(action) },
          variant = if (action == EmptyAction.AddLinks || action == EmptyAction.Add) {
            KetchButtonVariant.Tonal
          } else {
            KetchButtonVariant.Secondary
          },
        )
      }
    }
  }
}

/**
 * Six placeholder rows while a device sends its downloads for the first time, gently pulsing
 * unless motion is reduced.
 *
 * @param rowHeight height of each row.
 * @param chip size of the file chip placeholder.
 */
@Composable
internal fun SkeletonRows(rowHeight: Dp, chip: Dp, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val motion = KetchTheme.motion
  val alpha = if (motion.reduced) {
    1f
  } else {
    val transition = rememberInfiniteTransition(label = "skeleton")
    transition.animateFloat(
      initialValue = 1f,
      targetValue = SKELETON_DIM,
      animationSpec = infiniteRepeatable(tween(motion.xlong * 2), RepeatMode.Reverse),
      label = "skeletonAlpha",
    ).value
  }
  val description = stringResource(Res.string.downloads_empty_loading)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .alpha(alpha)
      .semantics { contentDescription = description },
  ) {
    repeat(SKELETON_ROWS) { index ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
        modifier = Modifier
          .fillMaxWidth()
          .height(rowHeight)
          .padding(horizontal = spacing.s4),
      ) {
        Box(Modifier.size(chip).background(colors.surfaceSunken, KetchTheme.shapes.sm))
        Box(
          Modifier
            .fillMaxWidth(SkeletonWidths[index % SkeletonWidths.size])
            .height(spacing.s3)
            .background(colors.surfaceSunken, KetchTheme.shapes.xs)
        )
        Spacer(Modifier.weight(1f))
        Box(
          Modifier
            .width(spacing.s16)
            .height(spacing.s3)
            .background(colors.surfaceSunken, KetchTheme.shapes.xs)
        )
      }
    }
  }
}

private const val SKELETON_ROWS = 6
private const val SKELETON_DIM = 0.55f
private val SkeletonWidths = listOf(0.42f, 0.3f, 0.5f, 0.36f, 0.46f, 0.26f)
private val MessageWidth: Dp = 360.dp
