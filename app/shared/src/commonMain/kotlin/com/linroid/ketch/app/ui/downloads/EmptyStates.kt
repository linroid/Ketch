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
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.links

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
  val title: String,
  val hint: String?,
  val icon: KetchIcon,
  val action: EmptyAction?,
  val actionLabel: String? = action?.label,
)

/** What the button of an empty tab or search does. */
internal enum class EmptyAction(val label: String) {
  ClearSearch("Clear search"),
  AddLinks("Add this link"),
  ShowAll("Show all downloads"),

  /** Opens the add sheet. */
  Add("Add a link"),
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
  deviceName: String,
  slots: Int?,
): EmptyCopy {
  val search = query.trim()
  if (search.isNotEmpty()) {
    val links = LinkParser.parseIntake(search).links()
    return if (links.isNotEmpty()) {
      val one = links.size == 1
      EmptyCopy(
        title = if (one) "This link isn't in your downloads" else {
          "These links aren't in your downloads"
        },
        hint = "Add ${if (one) "it" else "them"} to $deviceName instead.",
        icon = KetchIcon.Link,
        action = EmptyAction.AddLinks,
        actionLabel = if (one) EmptyAction.AddLinks.label else "Add these links",
      )
    } else {
      EmptyCopy(
        title = "No downloads match “$search”",
        hint = "Search looks at names, sites, folders and errors.",
        icon = KetchIcon.Search,
        action = EmptyAction.ClearSearch,
      )
    }
  }
  return when (filter) {
    StatusFilter.All -> EmptyCopy(
      title = "No downloads yet",
      hint = null,
      icon = KetchIcon.Active,
      action = null,
    )
    StatusFilter.Downloading -> EmptyCopy(
      title = "Nothing downloading",
      hint = "Downloads that are running show here.",
      icon = KetchIcon.Active,
      action = EmptyAction.ShowAll,
    )
    StatusFilter.Waiting -> EmptyCopy(
      title = "Nothing waiting",
      hint = slots?.let { "$deviceName runs ${runsAtATime(it)}." },
      icon = KetchIcon.Queued,
      action = EmptyAction.ShowAll,
    )
    StatusFilter.Paused -> EmptyCopy(
      title = "No paused downloads",
      hint = "Downloads you pause show here.",
      icon = KetchIcon.Pause,
      action = EmptyAction.ShowAll,
    )
    StatusFilter.Done -> EmptyCopy(
      title = "No finished downloads",
      hint = "Downloads show here once they finish.",
      icon = KetchIcon.Done,
      action = EmptyAction.ShowAll,
    )
    StatusFilter.Failed -> EmptyCopy(
      title = "Nothing needs attention",
      hint = "Failed and canceled downloads show here.",
      icon = KetchIcon.CheckCircle,
      action = EmptyAction.ShowAll,
    )
  }
}

/** The copy of a remote device that has no downloads yet, with a button that adds one to it. */
internal fun remoteEmptyCopy(deviceName: String): EmptyCopy = EmptyCopy(
  title = "Downloads on $deviceName will appear here",
  hint = null,
  icon = KetchIcon.Server,
  action = EmptyAction.Add,
  actionLabel = "Add a link to $deviceName",
)

/**
 * The copy of a remote device that cannot be reached and has sent no downloads yet: offline, or
 * refusing its access token when [unauthorized]. The banner above the page offers the way out.
 */
internal fun offlineCopy(deviceName: String, unauthorized: Boolean): EmptyCopy = EmptyCopy(
  title = if (unauthorized) "$deviceName needs a new access token" else "Can't reach $deviceName",
  hint = "Its downloads show here once it connects.",
  icon = KetchIcon.Server,
  action = null,
)

private fun runsAtATime(slots: Int): String =
  if (slots == 1) "one download at a time" else "$slots at a time"

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
        text = copy.title,
        style = KetchTheme.typography.titleM,
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
      )
      copy.hint?.let {
        Text(
          text = it,
          style = KetchTheme.typography.bodyS,
          color = colors.textSecondary,
          textAlign = TextAlign.Center,
        )
      }
      val action = copy.action
      if (action != null && copy.actionLabel != null) {
        Spacer(Modifier.height(spacing.s1))
        KetchButton(
          text = copy.actionLabel,
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
  Column(
    modifier = modifier
      .fillMaxWidth()
      .alpha(alpha)
      .semantics { contentDescription = "Loading downloads" },
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
