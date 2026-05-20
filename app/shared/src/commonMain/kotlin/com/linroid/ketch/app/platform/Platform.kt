package com.linroid.ketch.app.platform

/**
 * True on phone/tablet form factors (Android, iOS), false on desktop/web.
 * Drives UI choices that should diverge by form factor (e.g. ModalBottomSheet
 * vs AlertDialog).
 */
expect val isMobilePlatform: Boolean
