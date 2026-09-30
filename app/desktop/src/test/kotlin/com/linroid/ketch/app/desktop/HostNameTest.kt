package com.linroid.ketch.app.desktop

import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class HostNameTest {
  private val asked = mutableListOf<String>()

  private fun resolve(
    kernel: String? = null,
    command: String? = null,
    env: Map<String, String> = emptyMap(),
    lookUp: String? = null,
  ): String = localHostName(
    kernelHostName = { asked += "kernel"; kernel },
    hostnameCommand = { asked += "hostname"; command },
    env = { name -> asked += name; env[name] },
    lookUp = { asked += "lookUp"; lookUp },
  )

  @Test
  fun localHostName_kernelName_skipsCommandAndLookup() {
    assertEquals("build-box", resolve(kernel = "build-box\n", command = "other"))
    assertEquals(listOf("kernel"), asked)
  }

  @Test
  fun localHostName_hostnameCommand_dropsLocalSuffix() {
    val name = resolve(command = "Lins-MacBook-Pro.local\n", lookUp = "slow.local")
    assertEquals("Lins-MacBook-Pro", name)
    assertEquals(listOf("kernel", "hostname"), asked)
  }

  @Test
  fun localHostName_blankCommandOutput_prefersComputerNameOverHostname() {
    val env = mapOf("COMPUTERNAME" to "DESKTOP-1", "HOSTNAME" to "box")
    assertEquals("DESKTOP-1", resolve(command = " \n", env = env))
    assertEquals(listOf("kernel", "hostname", "COMPUTERNAME"), asked)
  }

  @Test
  fun localHostName_nothingWithoutLookup_looksUpLast() {
    assertEquals("studio", resolve(env = mapOf("HOSTNAME" to ""), lookUp = "studio.local"))
    assertEquals(listOf("kernel", "hostname", "COMPUTERNAME", "HOSTNAME", "lookUp"), asked)
  }

  @Test
  fun localHostName_noName_returnsFallback() {
    assertEquals(FALLBACK_HOST_NAME, resolve(lookUp = ".local"))
  }

  @Test
  fun lookUpHostName_slowLookup_givesUpAfterTimeout() {
    val release = CountDownLatch(1)
    try {
      assertNull(lookUpHostName(50.milliseconds) { release.await(); "late" })
    } finally {
      release.countDown()
    }
  }

  @Test
  fun lookUpHostName_failedLookup_returnsNull() {
    assertNull(lookUpHostName { throw UnknownHostException("studio") })
  }
}
