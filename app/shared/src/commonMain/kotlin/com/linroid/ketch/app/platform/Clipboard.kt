package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.links
import kotlinx.coroutines.flow.Flow

/**
 * The system clipboard, read with the care each platform asks for: [hasLink] peeks without
 * reading the text where the platform can, and [readText] is only called from a user action
 * unless [readsSilently], because phones, macOS and browsers tell or ask the user when an app
 * reads the clipboard.
 */
interface SystemClipboard {
  /**
   * Whether [readText] shows no notice and asks nothing, so the app may read the clipboard on its
   * own, such as when its window gains focus: Windows, Linux and Android before 12.
   */
  val readsSilently: Boolean

  /**
   * Whether the clipboard probably holds a link. Android 12+ and iOS answer from the system's
   * own detection without reading the text, so no paste notice appears. macOS only tells whether
   * there is text, so any text counts. The web cannot tell without asking for permission and
   * always answers `false`.
   */
  suspend fun hasLink(): Boolean

  /**
   * Text on the clipboard, or `null` when there is none. Call only from a user action unless
   * [readsSilently].
   */
  suspend fun readText(): String?

  /**
   * Puts [text] on the clipboard.
   *
   * @throws Exception when the system refuses, such as a browser without clipboard permission.
   */
  suspend fun writeText(text: String)

  /**
   * Text the user pastes while no text field has focus. Only the web reports these, from the
   * document's `paste` event, because there a key press alone cannot read the clipboard; the
   * browser fires no such event for a paste key press the app consumed. Elsewhere the flow is
   * empty and shortcuts call [readText].
   */
  val pasteEvents: Flow<String>
}

/** The [SystemClipboard] of this platform. */
@Composable
expect fun rememberSystemClipboard(): SystemClipboard

/** Whether [text] holds a link to download. Only its start is searched, so a huge clip is cheap. */
internal fun holdsLink(text: String): Boolean =
  LinkParser.parseIntake(text.take(MAX_LINK_SCAN_CHARS)).links().isNotEmpty()

private const val MAX_LINK_SCAN_CHARS = 64 * 1024
