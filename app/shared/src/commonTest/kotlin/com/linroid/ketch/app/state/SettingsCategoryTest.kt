package com.linroid.ketch.app.state

import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsCategoryTest {
  private val embedded = EmbeddedInstance(FakeKetchApi(), "MacBook Pro")
  private val remote = RemoteInstance(
    instance = FakeKetchApi(),
    remoteConfig = RemoteConfig(host = "nas.local"),
    connectionState = MutableStateFlow(ConnectionState.Connected),
  )

  @Test
  fun visible_embeddedDeviceThatShares_offersEveryPage() {
    assertEquals(
      SettingsCategory.entries.toList(),
      SettingsCategory.visible(discoverSupported = true, device = embedded, serverSupported = true),
    )
  }

  @Test
  fun visible_remoteDevice_leavesOutBitTorrentAndSharing() {
    val categories =
      SettingsCategory.visible(discoverSupported = true, device = remote, serverSupported = true)

    assertEquals(
      SettingsCategory.entries - SettingsCategory.BitTorrent - SettingsCategory.Sharing,
      categories,
    )
  }

  @Test
  fun visible_noServerHere_leavesOutSharing() {
    val categories =
      SettingsCategory.visible(discoverSupported = true, device = embedded, serverSupported = false)

    assertEquals(SettingsCategory.entries - SettingsCategory.Sharing, categories)
  }

  @Test
  fun visible_noDevice_offersOnlyTheAppPages() {
    val categories =
      SettingsCategory.visible(discoverSupported = false, device = null, serverSupported = false)

    assertEquals(
      listOf(
        SettingsCategory.General,
        SettingsCategory.Notifications,
        SettingsCategory.Integration,
        SettingsCategory.About,
      ),
      categories,
    )
  }

  @Test
  fun section_eachCategory_followsWhetherItsPageIsADevicePage() {
    val device = SettingsCategory.entries.filter { it.section == SettingsSection.Device }

    assertEquals(SettingsTarget.Page.entries.filter { it.isDevicePage }, device.map { it.page })
  }

  @Test
  fun of_everyPage_hasItsOwnCategory() {
    assertEquals(
      SettingsTarget.Page.entries,
      SettingsTarget.Page.entries.map { SettingsCategory.of(it).page },
    )
  }
}
