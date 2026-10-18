package com.linroid.ketch.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WebDavClientTest {
  @Test
  fun origin_ignoresDefaultPortsAndCase() {
    assertEquals(
      WebDavClient.origin("https://Cloud.Example:443/a"),
      WebDavClient.origin("https://cloud.example/b?x=1"),
    )
    assertEquals(WebDavClient.origin("http://h:80/a"), WebDavClient.origin("http://h/"))
    assertNotEquals(WebDavClient.origin("http://h/a"), WebDavClient.origin("https://h/a"))
    assertNotEquals(WebDavClient.origin("http://h:8080/a"), WebDavClient.origin("http://h/a"))
    assertNotEquals(WebDavClient.origin("http://h/a"), WebDavClient.origin("http://other/a"))
  }

  @Test
  fun requestTargetOf_keepsPathAndQuery() {
    assertEquals("/dav/a%20b?v=1", WebDavClient.requestTargetOf("https://h:8443/dav/a%20b?v=1#f"))
    assertEquals("/", WebDavClient.requestTargetOf("https://h"))
    assertEquals("/?q", WebDavClient.requestTargetOf("https://h?q"))
  }
}
