package com.linroid.ketch.config

import okio.FileSystem

internal expect val platformFileSystem: FileSystem

/**
 * Home directory that a leading `~` in a config file path expands to, or `null` on platforms
 * whose config files are not edited by hand.
 */
internal expect val userHomeDirectory: String?
