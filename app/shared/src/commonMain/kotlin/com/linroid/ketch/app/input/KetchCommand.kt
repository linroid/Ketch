package com.linroid.ketch.app.input

import androidx.compose.runtime.Immutable
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.icons.KetchIcon

/** Where the chords of a command are listened for. A chord runs one command per scope. */
enum class CommandScope {
  /** Anywhere in the main window. */
  Global,

  /** The download list while it has keyboard focus. */
  List,

  /** The add sheet while it is open. */
  Intake,

  /** The command palette while it is open. */
  Palette,

  /** The Discover page while it has keyboard focus: its composer, results and approvals. */
  Discover,
}

/**
 * Something the user can run from a shortcut, the menu bar, the tray, the command palette or a
 * tooltip. Every command lives in [KetchCommands]; the screens that run them bind the handlers.
 *
 * @property id stable identifier, such as "pauseAll" or "list.copyLink".
 * @property label sentence-case name, such as "Pause all".
 * @property icon glyph shown next to [label], or `null` for none.
 * @property scope where the chords are listened for.
 * @property alternates further chords that run the command on desktop keyboards, written once for
 *   Apple and PC keyboards as [KeyChord] is: [boundChords] lists them after [chords], but menus,
 *   tooltips and the shortcut sheet only show [chords].
 * @property inMenus whether the menu bar lists the command.
 * @property onWeb whether the web app offers the command; window commands belong to the
 *   browser there.
 * @property yieldsToTextField whether a focused text field keeps the chord for itself, as it
 *   does ⌘V and ⌘Z.
 */
@Immutable
class KetchCommand internal constructor(
  val id: String,
  val label: UiText,
  val icon: KetchIcon?,
  val scope: CommandScope = CommandScope.Global,
  private val mac: KeyChord?,
  private val pc: KeyChord? = mac,
  private val web: List<KeyChord>? = null,
  private val alternates: List<KeyChord> = emptyList(),
  val inMenus: Boolean = false,
  val onWeb: Boolean = true,
  val yieldsToTextField: Boolean = false,
) {
  /**
   * The chords that run this command on [platform], the one to show in menus and tooltips first.
   * The web app uses the desktop chord of the browser's platform unless the command has its own
   * web chords.
   */
  fun chords(platform: KeyboardPlatform): List<KeyChord> {
    val desktop = listOfNotNull(if (platform.isApple) mac else pc)
    return when {
      !platform.isWeb -> desktop
      !onWeb -> emptyList()
      else -> web ?: desktop
    }
  }

  /**
   * Every chord that runs this command on [platform]: its [chords], then on desktop keyboards the
   * alternates no menu, tooltip or shortcut sheet shows, such as ⌘+ beside ⌘= for Zoom in.
   */
  fun boundChords(platform: KeyboardPlatform): List<KeyChord> {
    val shown = chords(platform)
    return if (platform.isWeb) shown else shown + alternates
  }

  /** The label of the first chord on [platform], such as "⇧⌘P"; `null` when there is none. */
  fun shortcutLabel(platform: KeyboardPlatform = KeyboardPlatform.current): String? =
    chords(platform).firstOrNull()?.label(platform)

  override fun toString(): String = id
}
