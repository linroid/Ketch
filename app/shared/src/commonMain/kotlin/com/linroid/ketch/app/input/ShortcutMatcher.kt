package com.linroid.ketch.app.input

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

/**
 * What has focus when a key is pressed.
 *
 * @property overlay the open add sheet ([CommandScope.Intake]) or command palette
 *   ([CommandScope.Palette]), whose chords come first; `null` when neither is open.
 * @property listFocused whether the download list has keyboard focus.
 * @property textFieldFocused whether a text field has keyboard focus.
 * @property composing whether an input method is composing text.
 * @property menuOpen whether a menu is open.
 */
@Immutable
data class ShortcutContext(
  val overlay: CommandScope? = null,
  val listFocused: Boolean = false,
  val textFieldFocused: Boolean = false,
  val composing: Boolean = false,
  val menuOpen: Boolean = false,
) {
  init {
    require(overlay == null || overlay == CommandScope.Intake || overlay == CommandScope.Palette) {
      "Only the add sheet and the palette are overlays, not $overlay"
    }
  }
}

/**
 * Finds the command a key press runs on [platform], resolving ⌘ or Ctrl as its primary
 * modifier.
 *
 * The open overlay's chords come first, then the list's, then the global ones. Overlay chords
 * wait while an input method composes or a menu is open, and a global command never runs in
 * their place. List chords need the list to have focus, with no text field, composition or menu
 * active. Global chords always run, except chords a focused text field keeps: those of commands
 * that [KetchCommand.yieldsToTextField] and those that would type a character, such as the web
 * app's single keys.
 *
 * @param platform keyboard conventions to match with.
 * @param commands commands to match; a chord bound twice in one scope runs the first.
 */
class ShortcutMatcher(
  val platform: KeyboardPlatform = KeyboardPlatform.current,
  commands: List<KetchCommand> = KetchCommands.all,
) {
  private val bindings: Map<CommandScope, Map<KeyPress, Binding>> =
    commands.groupBy { it.scope }.mapValues { (_, scoped) ->
      buildMap {
        for (command in scoped) {
          for (chord in command.chords(platform)) {
            val press = chord.resolve(platform)
            if (press !in this) put(press, Binding(command, chord))
          }
        }
      }
    }

  /** The command [event] runs in [context], or `null`; only key-down events run commands. */
  fun match(event: KeyEvent, context: ShortcutContext): KetchCommand? =
    if (event.type == KeyEventType.KeyDown) match(event.toKeyPress(), context) else null

  /** The command [press] runs in [context], or `null` when it runs none. */
  fun match(press: KeyPress, context: ShortcutContext): KetchCommand? {
    val overlay = context.overlay
    if (overlay != null) {
      val binding = find(overlay, press)
      val waits = context.composing || context.menuOpen
      if (binding != null) return if (waits) null else binding.command
    }
    val listActive = overlay == null && context.listFocused && !context.textFieldFocused &&
      !context.composing && !context.menuOpen
    if (listActive) {
      find(CommandScope.List, press)?.let { return it.command }
    }
    val global = find(CommandScope.Global, press) ?: return null
    val textFieldKeeps = global.command.yieldsToTextField || global.chord.typesText(platform)
    return if (textFieldKeeps && context.textFieldFocused) null else global.command
  }

  private fun find(scope: CommandScope, press: KeyPress): Binding? = bindings[scope]?.get(press)

  private data class Binding(val command: KetchCommand, val chord: KeyChord)
}
