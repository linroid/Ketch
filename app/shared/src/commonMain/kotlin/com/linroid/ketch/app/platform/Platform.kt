package com.linroid.ketch.app.platform

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * True on phone/tablet form factors (Android, iOS), false on desktop/web.
 * Drives UI choices that should diverge by form factor (e.g. ModalBottomSheet
 * vs AlertDialog).
 */
expect val isMobilePlatform: Boolean

/** Pointer over a boundary that resizes sideways, such as a table column's; plain on touch. */
internal expect val HorizontalResizePointerIcon: PointerIcon

/** What success feedback the app's host plays, for its settings. */
internal enum class SuccessFeedbackSupport {
  /** None: the host plays no feedback. */
  None,

  /** The chime only. */
  Sound,

  /** The chime and a short vibration. */
  SoundAndVibration,
}

/** What this platform's host plays when a download finishes or Discover finds downloads. */
internal expect val successFeedbackSupport: SuccessFeedbackSupport
