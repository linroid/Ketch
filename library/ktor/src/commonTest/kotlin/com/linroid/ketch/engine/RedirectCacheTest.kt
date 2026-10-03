package com.linroid.ketch.engine

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class RedirectCacheTest {
  private val time = TestTimeSource()

  @Test
  fun get_afterTtl_forgetsTarget() = runTest {
    val cache = RedirectCache(ttl = 30.minutes, timeSource = time)
    cache.put(key("a"), target("a"))

    time += 29.minutes
    assertEquals(target("a").url, cache.get(key("a"))?.url)
    time += 1.minutes
    assertNull(cache.get(key("a")))
  }

  @Test
  fun put_beyondCapacity_dropsLeastRecentlyUsed() = runTest {
    val cache = RedirectCache(capacity = 2, timeSource = time)
    cache.put(key("a"), target("a"))
    cache.put(key("b"), target("b"))
    cache.get(key("a"))

    cache.put(key("c"), target("c"))

    assertNull(cache.get(key("b")))
    assertEquals(target("a").url, cache.get(key("a"))?.url)
    assertEquals(target("c").url, cache.get(key("c"))?.url)
  }

  @Test
  fun get_sameUrlWithOtherHeaders_misses() = runTest {
    val cache = RedirectCache(timeSource = time)
    cache.put(RedirectCache.Key(URL, mapOf("Cookie" to "a")), target("a"))

    assertNull(cache.get(RedirectCache.Key(URL, mapOf("Cookie" to "b"))))
  }

  private fun key(name: String) = RedirectCache.Key("$URL/$name", emptyMap())

  private fun target(name: String) =
    RedirectCache.Target(Url("https://cdn.example/$name"), emptyMap())

  private companion object {
    const val URL = "https://origin.example"
  }
}
