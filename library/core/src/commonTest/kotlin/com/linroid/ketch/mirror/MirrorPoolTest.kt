package com.linroid.ketch.mirror

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.mirror.Mirror
import com.linroid.ketch.core.mirror.MirrorPool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MirrorPoolTest {
  @Test
  fun failed_refusedCertificate_dropsTheMirrorAtOnce() = runTest {
    val pool = MirrorPool(listOf(Mirror("https://a.example/f"), Mirror("https://b.example/f")))
    val refused = KetchError.SourceError("http", detail = "The server's certificate has expired")

    assertTrue(pool.failed(pool.acquire(), refused))

    assertEquals(1, pool.usable())
  }

  @Test
  fun failed_networkError_keepsTheMirrorToRetry() = runTest {
    val pool = MirrorPool(listOf(Mirror("https://a.example/f"), Mirror("https://b.example/f")))

    assertTrue(pool.failed(pool.acquire(), KetchError.Network(Exception("reset"))))

    assertEquals(2, pool.usable())
  }
}
