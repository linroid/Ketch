package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.api.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxySettingsTest {
  private val saved = Proxy.Manual("http://me:secret@old.lan:3128", listOf("*.lan"))

  @Test
  fun withAddress_withoutCredentials_keepsSavedOnes() {
    val updated = withAddress(saved, "socks5://new.lan:1080")
    assertEquals(Proxy.Manual("socks5://me:secret@new.lan:1080", listOf("*.lan")), updated)
  }

  @Test
  fun withAddress_withCredentials_replacesSavedOnes() {
    val updated = withAddress(saved, "http://you:pass@new.lan:8080")
    assertEquals(Proxy.Manual("http://you:pass@new.lan:8080", listOf("*.lan")), updated)
  }

  @Test
  fun withAddress_withoutSavedProxy_takesTheTypedOne() {
    assertEquals(Proxy.Manual("http://new.lan:8080"), withAddress(null, "http://new.lan:8080"))
  }

  @Test
  fun bypassEntries_dropsBlankEntries() {
    assertEquals(listOf("*.lan", "10.0.0.0/8"), bypassEntries(" *.lan,, 10.0.0.0/8 , "))
  }
}
