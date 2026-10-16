package com.linroid.ketch.cli

import com.linroid.ketch.config.TorrentUploadMode
import com.linroid.ketch.torrent.TorrentUploadPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentUploadSettingsTest {
  @Test
  fun everyUploadModeMapsToADistinctPolicy() {
    val policies = TorrentUploadMode.entries.associateWith(::torrentUploadPolicy)

    assertEquals(TorrentUploadMode.entries.size, policies.values.toSet().size)
    assertEquals(TorrentUploadPolicy.DISABLED, policies[TorrentUploadMode.Off])
    assertEquals(
      TorrentUploadPolicy.WHILE_DOWNLOADING,
      policies[TorrentUploadMode.WhileDownloading],
    )
    assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, policies[TorrentUploadMode.Seed])
  }
}
