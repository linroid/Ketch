package com.linroid.ketch.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavUrlTest {
  @Test
  fun schemes_mapToHttpAndHttps() {
    assertEquals("http://host/a.bin", WebDavUrl.parse("webdav://host/a.bin").httpUrl)
    assertEquals("http://host/a.bin", WebDavUrl.parse("dav://host/a.bin").httpUrl)
    assertEquals("https://host/a.bin", WebDavUrl.parse("webdavs://host/a.bin").httpUrl)
    assertEquals("https://host:8443/a.bin", WebDavUrl.parse("DAVS://host:8443/a.bin").httpUrl)
    assertTrue(WebDavUrl.parse("davs://host/a").isSecure)
    assertFalse(WebDavUrl.parse("dav://host/a").isSecure)
  }

  @Test
  fun isWebDav_checksTheScheme() {
    assertTrue(WebDavUrl.isWebDav("webdavs://host/a"))
    assertTrue(WebDavUrl.isWebDav("Dav://host/a"))
    assertFalse(WebDavUrl.isWebDav("https://host/a"))
    assertFalse(WebDavUrl.isWebDav("ftp://host/a"))
    assertFalse(WebDavUrl.isWebDav("webdav:host"))
  }

  @Test
  fun credentials_areDecodedAndLeftOutOfTheHttpUrl() {
    val url = WebDavUrl.parse("davs://us%40er:p%3Ass%20w@cloud.example/remote.php/dav/f.zip")
    assertEquals("https://cloud.example/remote.php/dav/f.zip", url.httpUrl)
    assertEquals("us@er", url.credentials?.user)
    assertEquals("p:ss w", url.credentials?.password)
    assertFalse("p:ss w" in url.credentials.toString())
    assertFalse("p:ss" in url.toString())
  }

  @Test
  fun credentials_withoutPassword() {
    val url = WebDavUrl.parse("dav://alice@host/a")
    assertEquals("alice", url.credentials?.user)
    assertEquals("", url.credentials?.password)
    assertNull(WebDavUrl.parse("dav://host/a").credentials)
  }

  @Test
  fun requestTarget_keepsPathAndQuery_withoutFragment() {
    val url = WebDavUrl.parse("dav://host/dir/My%20File.txt?version=2#part")
    assertEquals("/dir/My%20File.txt?version=2", url.requestTarget)
    assertEquals("http://host/dir/My%20File.txt?version=2", url.httpUrl)
    assertEquals("/", WebDavUrl.parse("dav://host").requestTarget)
    assertEquals("/?a=1", WebDavUrl.parse("dav://host?a=1").requestTarget)
  }

  @Test
  fun requestTarget_encodesWhatATargetCannotHold() {
    val url = WebDavUrl.parse("dav://host/共享/My File[1].txt?q=a b")
    assertEquals("/%E5%85%B1%E4%BA%AB/My%20File%5B1%5D.txt?q=a%20b", url.requestTarget)
    assertEquals("http://host" + url.requestTarget, url.httpUrl)
    assertEquals("My File[1].txt", url.fileName)
    assertEquals("/a%20b", WebDavUrl.parse("dav://host/a%20b").requestTarget)
  }

  @Test
  fun fileName_isTheDecodedLastSegment() {
    assertEquals("My File.txt", WebDavUrl.parse("dav://host/dir/My%20File.txt?x=1").fileName)
    assertEquals("報告.pdf", WebDavUrl.parse("dav://host/%E5%A0%B1%E5%91%8A.pdf").fileName)
    assertNull(WebDavUrl.parse("dav://host/dir/").fileName)
  }

  @Test
  fun ipv6Host() {
    assertEquals("https://[::1]:8080/a", WebDavUrl.parse("davs://u:p@[::1]:8080/a").httpUrl)
  }

  @Test
  fun invalidLinks_areRefused() {
    assertFailsWith<IllegalArgumentException> { WebDavUrl.parse("https://host/a") }
    assertFailsWith<IllegalArgumentException> { WebDavUrl.parse("dav:///a") }
    assertFailsWith<IllegalArgumentException> { WebDavUrl.parse("dav://user@/a") }
    assertFailsWith<IllegalArgumentException> { WebDavUrl.parse("dav://u:%zz@host/a") }
    assertFailsWith<IllegalArgumentException> { WebDavUrl.parse("dav://u:%E5@host/a") }
    assertFailsWith<IllegalArgumentException> { WebDavUrl.parse("dav://u:%4@host/a") }
  }
}
