package com.linroid.ketch.app.state

/**
 * The name a download saved as [name] in [folder] of this device would get beside a file that
 * already has that name, such as `report (1).pdf`; `null` when no file has it, and where it
 * cannot be told: an Android `content://` folder, and the web app, which has no files.
 * Looks at the file system off the caller's thread.
 */
internal expect suspend fun renamedIfFileExists(folder: String, name: String): String?
