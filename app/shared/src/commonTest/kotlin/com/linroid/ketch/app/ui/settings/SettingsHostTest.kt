package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.SettingsTarget.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsHostTest {
  private val generic = SettingsTarget(Page.General)

  @Test
  fun pageFor_deepLink_opensThatPage() {
    assertEquals(Page.Speed, pageFor(SettingsTarget(Page.Speed), Page.About, Page.Network, false))
  }

  @Test
  fun pageFor_generalOnADevice_isADeepLink() {
    val target = SettingsTarget(Page.General, deviceId = "nas.local:8642")

    assertEquals(Page.General, pageFor(target, current = Page.Speed, last = null, list = false))
  }

  @Test
  fun pageFor_genericOpenWhileShowing_keepsThePageOnScreen() {
    assertEquals(Page.Sharing, pageFor(generic, Page.Sharing, Page.Network, list = false))
  }

  @Test
  fun pageFor_genericOpen_reopensTheLastPage() {
    assertEquals(Page.Network, pageFor(generic, current = null, last = Page.Network, list = false))
    assertEquals(Page.Network, pageFor(null, current = null, last = Page.Network, list = false))
  }

  @Test
  fun pageFor_genericOpenFirstTime_showsGeneral() {
    assertEquals(Page.General, pageFor(generic, current = null, last = null, list = false))
  }

  @Test
  fun pageFor_genericOpenOnAPhone_startsAtTheList() {
    assertNull(pageFor(generic, current = null, last = Page.Network, list = true))
  }

  @Test
  fun pageNamed_unknownName_isNull() {
    assertEquals(Page.Speed, pageNamed("Speed"))
    assertNull(pageNamed("RemoteAccess"))
    assertNull(pageNamed(null))
  }

  @Test
  fun fallbackPage_devicePageNoLongerOffered_showsTheDevicesDownloads() {
    val remotePages = SettingsCategory.entries - SettingsCategory.Sharing -
      SettingsCategory.BitTorrent

    assertEquals(SettingsCategory.Downloads, fallbackPage("Sharing", remotePages))
  }

  @Test
  fun fallbackPage_appPageNoLongerOffered_showsGeneral() {
    val withoutDiscover = SettingsCategory.entries - SettingsCategory.Discover

    assertEquals(SettingsCategory.General, fallbackPage("Discover", withoutDiscover))
  }

  @Test
  fun fallbackPage_noDevice_showsGeneral() {
    val appPages = SettingsCategory.entries.filter { !it.page.isDevicePage }

    assertEquals(SettingsCategory.General, fallbackPage("Speed", appPages))
  }
}
