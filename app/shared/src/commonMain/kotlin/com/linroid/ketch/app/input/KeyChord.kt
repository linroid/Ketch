package com.linroid.ketch.app.input

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import kotlin.concurrent.Volatile

/**
 * Keyboard conventions: which key [KeyChord.primary] stands for, how chords are printed and
 * which chords a command uses. iPad keyboards follow [Mac] and Android keyboards follow [Pc].
 *
 * @property isApple whether ⌘ is the primary modifier and chords print as glyphs ("⇧⌘P").
 * @property isWeb whether the app runs in a browser, which keeps some chords for itself.
 */
enum class KeyboardPlatform(val isApple: Boolean, val isWeb: Boolean) {
  /** macOS and iPadOS. */
  Mac(isApple = true, isWeb = false),

  /** Windows, Linux and Android. */
  Pc(isApple = false, isWeb = false),

  /** The web app in a browser on a Mac, iPhone or iPad. */
  WebMac(isApple = true, isWeb = true),

  /** The web app in any other browser. */
  WebPc(isApple = false, isWeb = true);

  companion object {
    /** The conventions of the device the app runs on. */
    val current: KeyboardPlatform get() = override ?: detected

    /**
     * Conventions [current] reports instead of the device's, for snapshots that render the app
     * as it looks on another platform; `null` reports the device's.
     */
    @Volatile
    internal var override: KeyboardPlatform? = null

    private val detected: KeyboardPlatform by lazy { detectKeyboardPlatform() }
  }
}

internal expect fun detectKeyboardPlatform(): KeyboardPlatform

/**
 * A key and the modifiers held with it, written once for Apple and PC keyboards.
 *
 * [primary] is ⌘ on Apple keyboards and Ctrl elsewhere. [ctrl] is the Control key itself, which
 * only Apple keyboards tell apart from [primary], so a PC chord never sets both.
 *
 * @property key the key pressed with the modifiers; only keys with a printable name are allowed.
 * @property primary whether ⌘ (Apple) or Ctrl (elsewhere) is held.
 * @property shift whether Shift is held.
 * @property alt whether ⌥ (Apple) or Alt (elsewhere) is held.
 * @property ctrl whether the Control key is held in addition to [primary], as in ⌃⌘S.
 */
@Immutable
data class KeyChord(
  val key: Key,
  val primary: Boolean = false,
  val shift: Boolean = false,
  val alt: Boolean = false,
  val ctrl: Boolean = false,
) {
  init {
    require(key in keyNames) { "Key ${key.keyCode} has no printable name" }
  }

  /** The keys [platform] reports when this chord is pressed. */
  fun resolve(platform: KeyboardPlatform): KeyPress = KeyPress(
    key = key,
    meta = primary && platform.isApple,
    ctrl = ctrl || (primary && !platform.isApple),
    alt = alt,
    shift = shift,
  )

  /** Prints this chord the way [platform] does, such as "⇧⌘P", "Ctrl+Shift+P", "n" or "?". */
  fun label(platform: KeyboardPlatform): String {
    val noModifiers = !primary && !alt && !ctrl
    if (noModifiers && shift && key == Key.Slash) return "?"
    val name = keyName(key, platform.isApple)
    if (noModifiers && !shift && key in letterKeys) return name.lowercase()
    if (platform.isApple) {
      return buildString {
        if (ctrl) append('⌃')
        if (alt) append('⌥')
        if (shift) append('⇧')
        if (primary) append('⌘')
        append(name)
      }
    }
    return listOfNotNull(
      "Ctrl".takeIf { primary || ctrl },
      ALT.takeIf { alt },
      "Shift".takeIf { shift },
      name,
    ).joinToString("+")
  }

  /**
   * Whether pressing this chord would type a character into a focused text field: a printable
   * key with no primary or Control key, and no ⌥ on Apple keyboards, where ⌥ types symbols.
   */
  internal fun typesText(platform: KeyboardPlatform): Boolean =
    !primary && !ctrl && (!alt || platform.isApple) && key in printableKeys
}

/**
 * How [platform] names the ⌥ (Alt) key on its own, as its key cap does: "⌥" on Apple keyboards,
 * "Alt" elsewhere.
 */
internal fun altKeyName(platform: KeyboardPlatform): String = if (platform.isApple) "⌥" else ALT

/**
 * A key and the physical modifiers held with it, as an event reports them.
 *
 * @property key the key pressed.
 * @property meta whether ⌘ (or the Windows key) is held.
 * @property ctrl whether Control is held.
 * @property alt whether ⌥ or Alt is held.
 * @property shift whether Shift is held.
 */
@Immutable
data class KeyPress(
  val key: Key,
  val meta: Boolean = false,
  val ctrl: Boolean = false,
  val alt: Boolean = false,
  val shift: Boolean = false,
)

/** The key and the modifiers of this event. */
fun KeyEvent.toKeyPress(): KeyPress = KeyPress(
  key = key,
  meta = isMetaPressed,
  ctrl = isCtrlPressed,
  alt = isAltPressed,
  shift = isShiftPressed,
)

// Key names stay English on every language: they match the legends printed on keyboards.
private const val ALT = "Alt"

private val letterKeys = listOf(
  Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
  Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
)

/** The digit keys 0 to 9, indexed by their digit. */
internal val digitKeys: List<Key> = listOf(
  Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight,
  Key.Nine,
)

private val symbolNames = mapOf(
  Key.Comma to ",",
  Key.Slash to "/",
  Key.Equals to "=",
  Key.Minus to "-",
  Key.Spacebar to "Space",
)

private val printableKeys: Set<Key> = (letterKeys + digitKeys + symbolNames.keys).toSet()

private val keyNames: Map<Key, String> = buildMap {
  letterKeys.forEachIndexed { index, key -> put(key, ('A' + index).toString()) }
  digitKeys.forEachIndexed { index, key -> put(key, index.toString()) }
  putAll(symbolNames)
  put(Key.Enter, "Enter")
  put(Key.Escape, "Esc")
  put(Key.Backspace, "Backspace")
  put(Key.Delete, "Delete")
  put(Key.DirectionUp, "↑")
  put(Key.DirectionDown, "↓")
  put(Key.DirectionLeft, "←")
  put(Key.DirectionRight, "→")
  put(Key.MoveHome, "Home")
  put(Key.MoveEnd, "End")
  put(Key.PageUp, "PgUp")
  put(Key.PageDown, "PgDn")
}

private val appleKeyNames = mapOf(
  Key.Enter to "↩",
  Key.Backspace to "⌫",
  Key.Delete to "⌦",
  Key.Minus to "−",
)

private fun keyName(key: Key, apple: Boolean): String =
  (if (apple) appleKeyNames[key] else null) ?: keyNames.getValue(key)
