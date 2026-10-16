package com.linroid.ketch.torrent

/**
 * Controls whether verified torrent pieces may be uploaded to peers. The policies apply alike to
 * v1, v2 and hybrid torrents.
 */
enum class TorrentUploadPolicy {
  /**
   * Never upload file payload. Peers are never unchoked, the info dictionary is not offered
   * (`ut_metadata`, so magnet peers cannot fetch it from this device), and v2 and hybrid
   * torrents reject every BEP 52 hash request.
   */
  DISABLED,

  /** Upload while selected files are downloading, then stop. */
  WHILE_DOWNLOADING,

  /** Continue uploading after selected files finish, until removed or closed. */
  SEED_AFTER_COMPLETION,
}
