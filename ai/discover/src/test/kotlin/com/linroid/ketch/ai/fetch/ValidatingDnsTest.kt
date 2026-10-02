package com.linroid.ketch.ai.fetch

import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValidatingDnsTest {

  @Test
  fun lookup_publicHost_returnsItsAddresses() {
    val dns = ValidatingDns(UrlValidator(resolve = fakeDns("example.com" to "93.184.215.14")))

    assertEquals(listOf(InetAddress.getByName("93.184.215.14")), dns.lookup("example.com"))
  }

  @Test
  fun lookup_hostRebindsToLoopbackAfterValidation_isRefused() = runTest {
    val validator = UrlValidator(resolve = rebindingDns("93.184.215.14", "127.0.0.1"))
    val dns = ValidatingDns(validator)

    assertIs<ValidationResult.Valid>(validator.validate("https://rebind.example/"))
    // OkHttp treats an UnknownHostException from Dns as a failed lookup and does not connect.
    val error = assertFailsWith<UnknownHostException> { dns.lookup("rebind.example") }
    assertTrue(error.message.orEmpty().contains("127.0.0.1"), error.message)
  }

  @Test
  fun lookup_publicAndPrivateAddresses_isRefusedRatherThanFiltered() {
    // Same rule as UrlValidator.validate: one blocked address blocks the host.
    val validator = UrlValidator(resolve = {
      arrayOf(InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.0.0.5"))
    })

    assertFailsWith<BlockedHostException> { ValidatingDns(validator).lookup("mixed.example") }
  }
}
