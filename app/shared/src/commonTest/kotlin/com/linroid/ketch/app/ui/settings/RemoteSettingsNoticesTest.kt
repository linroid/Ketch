package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.api.KetchFeatures
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_downloads_remote
import ketch.app.shared.generated.resources.settings_downloads_remote_saved
import ketch.app.shared.generated.resources.settings_speed_remote
import ketch.app.shared.generated.resources.settings_speed_remote_saved
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteSettingsNoticesTest {
  private val saving = KetchFeatures.ALL + KetchFeatures.CONFIG_PERSISTED

  @Test
  fun downloadsRemoteNotice_serverSaves_saysSaved() {
    assertEquals(Res.string.settings_downloads_remote_saved, downloadsRemoteNotice(saving))
  }

  @Test
  fun downloadsRemoteNotice_olderOrUnreportedServer_saysUntilItRestarts() {
    assertEquals(Res.string.settings_downloads_remote, downloadsRemoteNotice(KetchFeatures.ALL))
    assertEquals(Res.string.settings_downloads_remote, downloadsRemoteNotice(emptySet()))
  }

  @Test
  fun speedRemoteNotice_followsWhetherTheServerSaves() {
    assertEquals(Res.string.settings_speed_remote_saved, speedRemoteNotice(saving))
    assertEquals(Res.string.settings_speed_remote, speedRemoteNotice(KetchFeatures.ALL))
  }
}
