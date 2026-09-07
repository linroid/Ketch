package com.linroid.ketch.torrent

/** Controls whether verified torrent pieces may be uploaded to peers. */
enum class TorrentUploadPolicy {
  /** Never upload file payload. */
  DISABLED,

  /** Upload while selected files are downloading, then stop. */
  WHILE_DOWNLOADING,

  /** Continue uploading after selected files finish, until removed or closed. */
  SEED_AFTER_COMPLETION,
}
