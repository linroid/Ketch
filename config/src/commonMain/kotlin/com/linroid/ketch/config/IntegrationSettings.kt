package com.linroid.ketch.config

import kotlinx.serialization.Serializable

/**
 * How the desktop app hooks into the operating system, persisted under
 * `[integration]`.
 *
 * Packaged apps on macOS and Linux declare their link and file handlers when
 * installed. Where that needs an explicit opt-in, such as on Windows, these
 * record the user's choice so the app can register again after it moves or
 * updates.
 *
 * @property magnetHandler whether Ketch registers itself to open `magnet:` links.
 * @property torrentFileHandler whether Ketch registers itself to open `.torrent` files.
 */
@Serializable
data class IntegrationSettings(
  val magnetHandler: Boolean = false,
  val torrentFileHandler: Boolean = false,
)
