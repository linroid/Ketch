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

/**
 * Whether this platform's host keeps the system awake while downloads run (`[power] keepAwake`):
 * the desktop and Android apps. iOS suspends apps in the background, and the web app has no
 * downloads of its own.
 */
internal expect val keepAwakeSupported: Boolean

/**
 * Whether this platform's torrent engine finds peers on the local network (BEP 14): the desktop
 * and Android apps. iOS needs a multicast entitlement the app does not have, and the web app has
 * no engine of its own.
 */
internal expect val localPeerDiscoverySupported: Boolean

/**
 * Whether the app offers a zoom of its own (`[appearance] zoom`): the desktop app, whose text can
 * be too small on a large display the system does not scale. Phones follow the system's display
 * and text size, and browsers zoom the web app themselves.
 */
internal expect val zoomSupported: Boolean
