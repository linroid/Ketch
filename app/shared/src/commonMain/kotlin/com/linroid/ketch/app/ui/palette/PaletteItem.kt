package com.linroid.ketch.app.ui.palette

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.TaskKey

/**
 * Where a palette row comes from. Rows that match equally well are listed in this order.
 *
 * @property title heading of the provider's rows while nothing is typed.
 */
internal enum class PaletteProvider(val title: String) {
  /** Links in the typed or pasted text, to download on a device. */
  Links("Download"),

  /** A typed speed such as "5m", for the Slow lane or the speed limit. */
  Speed("Speed"),

  /** Commands of the registry, with live counts. */
  Commands("Commands"),

  /** Downloads whose name matches. */
  Downloads("Downloads"),

  /** Tabs, destinations and Settings pages. */
  Navigation("Go to"),

  /** Other devices to switch to, and the commands that act on one device. */
  Devices("Devices"),

  /** Searching the Downloads list or Discover for the text. */
  Fallback("Search"),
}

/** The leading glyph of a palette row. */
@Immutable
internal sealed interface PaletteIcon {
  /** A glyph of the icon set. */
  data class Glyph(val icon: KetchIcon) : PaletteIcon

  /** The file-type tile of a download named [name], from [url]. */
  data class File(val name: String, val url: String) : PaletteIcon

  /** The pennant of the device [deviceId] called [name]. */
  data class Device(val deviceId: String, val name: String) : PaletteIcon
}

/** What running a palette row does. [PaletteRunner] carries it out. */
@Immutable
internal sealed interface PaletteAction {
  /** Runs [command] as its shortcut does. */
  data class Command(val command: KetchCommand) : PaletteAction

  /**
   * Adds [urls] on the device [deviceId] at once when [now], otherwise opens the add sheet with
   * [text] for that device, as magnets and links with headers need.
   */
  data class Download(
    val text: String,
    val urls: List<String>,
    val deviceId: String,
    val now: Boolean,
  ) : PaletteAction

  /** Opens the add sheet with [text] for the device [deviceId]. */
  data class AddWithOptions(val text: String, val deviceId: String?) : PaletteAction

  /** Sets the Slow lane of the active device to [limit] and turns it on. */
  data class SlowLane(val limit: SpeedLimit) : PaletteAction

  /** Caps every download of the active device at [limit]. */
  data class SpeedCap(val limit: SpeedLimit) : PaletteAction

  /** Lets the active device download at full speed: no Slow lane, no cap. */
  data object FullSpeed : PaletteAction

  /** Runs [verb] on every task of the device [deviceId]. */
  data class DeviceBatch(val deviceId: String, val verb: BatchVerb) : PaletteAction

  /** Shows the device [deviceId]. */
  data class SwitchDevice(val deviceId: String) : PaletteAction

  /** Runs [action] on the task [key]. */
  data class Task(val key: TaskKey, val action: RowAction) : PaletteAction

  /** Opens Settings at [page]. */
  data class Settings(val page: SettingsTarget.Page) : PaletteAction

  /** Filters the Downloads list with [query]. */
  data class Search(val query: String) : PaletteAction

  /** Searches Discover for [query]. */
  data class Discover(val query: String) : PaletteAction
}

/** What a device-wide command does to the tasks of one device. */
internal enum class BatchVerb(val label: String) {
  Pause("Pause all"),
  Resume("Resume all"),
  Retry("Retry failed"),
}

/**
 * A row of the command palette.
 *
 * @property id stable identity, remembered by [PaletteHistory]; empty for rows made from the
 *   typed text, such as a link, which are never remembered.
 * @property provider where the row comes from.
 * @property title what the row does or shows.
 * @property subtitle short context after the title, such as a count or a device.
 * @property icon the leading glyph.
 * @property action what ↩ does.
 * @property alternate what ⌘↩ does, if anything.
 * @property shortcut the chord that runs the same thing outside the palette, such as "⇧⌘P".
 * @property verb what ↩ does, shown on the highlighted row, such as "Open" or "Pause".
 * @property keywords other words the row answers to, such as "unlimited" for Full speed.
 * @property matchQuery the text matched in place of what was typed, such as a search's words
 *   without its `is:` tokens; blank matches every row.
 * @property direct whether the row was made from the typed text and comes first.
 * @property searchOnly whether the row waits for something typed, so the list shown before
 *   leaves it out.
 */
@Immutable
internal data class PaletteItem(
  val id: String,
  val provider: PaletteProvider,
  val title: String,
  val icon: PaletteIcon,
  val action: PaletteAction,
  val subtitle: String? = null,
  val alternate: PaletteAction? = null,
  val shortcut: String? = null,
  val verb: String? = null,
  val keywords: List<String> = emptyList(),
  val matchQuery: String? = null,
  val direct: Boolean = false,
  val searchOnly: Boolean = false,
)

/**
 * The rows run last, most recent first, which the palette lists before anything is typed and
 * ranks first among rows that match as well.
 *
 * The rows live as long as the app's process; [Default] is the one every window shares.
 */
@Stable
internal class PaletteHistory(ids: List<String> = emptyList()) {
  /** Ids of the rows run last, most recent first, at most [SIZE]. */
  var recent: List<String> by mutableStateOf(ids.distinct().take(SIZE))
    private set

  /** Remembers that the row [id] ran; rows made from typed text are not remembered. */
  fun record(id: String) {
    if (id.isEmpty()) return
    recent = (listOf(id) + recent.filter { it != id }).take(SIZE)
  }

  companion object {
    /** How many rows are remembered. */
    const val SIZE: Int = 5

    /** The history of the app's palette. */
    val Default: PaletteHistory = PaletteHistory()
  }
}
