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
}

/**
 * Something the user can run from a shortcut, the menu bar, the tray, the command palette or a
 * tooltip. Every command lives in [KetchCommands]; the screens that run them bind the handlers.
 *
 * @property id stable identifier, such as "pauseAll" or "list.copyLink".
 * @property label sentence-case name, such as "Pause all".
 * @property icon glyph shown next to [label], or `null` for none.
 * @property scope where the chords are listened for.
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

  /** The label of the first chord on [platform], such as "⇧⌘P"; `null` when there is none. */
  fun shortcutLabel(platform: KeyboardPlatform = KeyboardPlatform.current): String? =
    chords(platform).firstOrNull()?.label(platform)

  override fun toString(): String = id
}
