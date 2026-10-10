package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.ProxyMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxySettingsTest {
  private val saved = ProxyConfig(
    url = "http://old.lan:3128",
    username = "me",
    password = "secret",
    bypass = listOf("*.lan"),
  )

  @Test
  fun withAddress_withoutCredentials_keepsSavedOnes() {
    val updated = withAddress(saved, "socks5://new.lan:1080")
    assertEquals(
      ProxyConfig(ProxyMode.MANUAL, "socks5://new.lan:1080", "me", "secret", listOf("*.lan")),
      updated,
    )
  }

  @Test
  fun withAddress_withCredentials_replacesSavedOnes() {
    val updated = withAddress(saved, "http://you:pass@new.lan:8080")
    assertEquals("http://new.lan:8080", updated.url)
    assertEquals("you", updated.username)
    assertEquals("pass", updated.password)
    assertEquals(listOf("*.lan"), updated.bypass)
  }

  @Test
  fun bypassEntries_dropsBlankEntries() {
    assertEquals(listOf("*.lan", "10.0.0.0/8"), bypassEntries(" *.lan,, 10.0.0.0/8 , "))
  }
}
