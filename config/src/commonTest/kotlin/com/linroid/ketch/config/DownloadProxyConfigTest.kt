package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ProxyAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadProxyConfigTest {

  @Test
  fun `proxy decodes from a hand-written download proxy table`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[download]
      |maxConnectionsPerDownload = 8
      |
      |[download.proxy]
      |mode = "manual"
      |url = "socks5://127.0.0.1:1080"
      |username = "me"
      |password = "p@ss w0rd"
      |bypass = ["*.lan", "10.0.0.0/8"]
      """.trimMargin(),
    )
    assertEquals(8, decoded.download.maxConnectionsPerDownload)
    assertEquals(
      Proxy.Manual(
        ProxyAddress(ProxyAddress.Type.SOCKS5, "127.0.0.1", 1080, "me", "p@ss w0rd"),
        bypass = listOf("*.lan", "10.0.0.0/8"),
      ),
      decoded.download.proxy,
    )
  }

  @Test
  fun `a proxy table kept from an older version loads as its mode`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[download.proxy]
      |mode = "direct"
      |url = "http://proxy.lan:3128"
      """.trimMargin(),
    )
    assertEquals(Proxy.Direct, decoded.download.proxy)
  }

  @Test
  fun `proxies saved by the apps load back`() {
    val manual = Proxy.Manual("http://me:secret@proxy.lan:3128", listOf("<local>"))
    for (proxy in listOf(Proxy.System, Proxy.Direct, manual)) {
      val config = KetchConfig(download = DownloadConfig(proxy = proxy))
      val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), config)
      val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
      assertEquals(proxy, decoded.download.proxy, encoded)
    }
  }
}
