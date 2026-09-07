package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorrentPolicyTest {
  @Test
  fun uploadPolicy_explicitPolicyOverridesLegacyFlag() {
    val config = TorrentConfig(enableUpload = true, uploadPolicy = TorrentUploadPolicy.DISABLED)
    assertEquals(TorrentUploadPolicy.DISABLED, config.effectiveUploadPolicy)
    assertEquals(
      TorrentUploadPolicy.SEED_AFTER_COMPLETION,
      TorrentConfig(enableUpload = true).effectiveUploadPolicy
    )
    assertEquals(
      TorrentUploadPolicy.DISABLED,
      config.copy(uploadPolicy = null, enableUpload = false).effectiveUploadPolicy
    )
  }

  @Test
  fun config_invalidResourceLimitsRejected() {
    assertFailsWith<IllegalArgumentException> { TorrentConfig(maxActiveTorrents = 0) }
    assertFailsWith<IllegalArgumentException> { TorrentConfig(connectionsPerTorrent = 0) }
    assertFailsWith<IllegalArgumentException> { TorrentConfig(listenPort = 65536) }
    assertFailsWith<IllegalArgumentException> { TorrentConfig(maxMetadataBytes = 0) }
    assertFailsWith<IllegalArgumentException> { TorrentConfig(maxBufferedBytes = 16383) }
    assertFailsWith<IllegalArgumentException> { TorrentConfig(metadataTimeoutSeconds = -1) }
  }
}
