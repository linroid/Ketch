package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import io.ktor.network.tls.tls
import kotlinx.coroutines.test.runTest
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RealFtpClientTlsTest {
  private val content = ByteArray(64 * 1024) { (it * 31 % 251).toByte() }
  private var tlsUpgrades = 0

  private fun client(server: ScriptedFtpServer) = RealFtpClient(
    host = "localhost",
    port = server.port,
    secureSocket = { connection, serverName, context ->
      tlsUpgrades++
      connection.tls(context) {
        this.serverName = serverName
        trustManager = testCertificate.trustManager
      }
    },
  )

  private suspend fun RealFtpClient.download(): ByteArray {
    val received = mutableListOf<Byte>()
    retrieve("/file.bin") { received += it.toList() }
    return received.toByteArray()
  }

  @Test
  fun retrieve_afterProtP_readsDataOverTls() = runTest {
    ScriptedFtpServer(content, testCertificate.sslContext).use { server ->
      val client = client(server)
      try {
        client.connect()
        client.upgradeToTls()
        client.login("anonymous", "guest")
        client.setBinaryMode()

        assertContentEquals(content, client.download())
      } finally {
        client.disconnect()
      }
      assertTrue("PROT P" in server.commands)
      assertEquals(1, server.dataHandshakes)
      // The control connection and the data connection
      assertEquals(2, tlsUpgrades)
    }
  }

  @Test
  fun retrieve_withoutTls_readsPlainData() = runTest {
    ScriptedFtpServer(content, testCertificate.sslContext).use { server ->
      val client = client(server)
      try {
        client.connect()
        client.login("anonymous", "guest")
        client.setBinaryMode()

        assertContentEquals(content, client.download())
      } finally {
        client.disconnect()
      }
      assertEquals(0, tlsUpgrades)
      assertEquals(0, server.dataHandshakes)
      // A TLS ClientHello would show up here.
      assertEquals(0, server.clientDataBytes.size)
    }
  }

  @Test
  fun retrieve_failedDataHandshake_throwsNetworkErrorWithoutData() = runTest {
    val clearText = "clear text that is not a TLS record".encodeToByteArray()
    ScriptedFtpServer(clearText, testCertificate.sslContext, sendsPlainData = true).use { server ->
      val client = client(server)
      val received = mutableListOf<ByteArray>()
      try {
        client.connect()
        client.upgradeToTls()
        client.login("anonymous", "guest")
        client.setBinaryMode()

        assertFailsWith<KetchError.Network> {
          client.retrieve("/file.bin") { received += it }
        }
      } finally {
        client.disconnect()
      }
      assertTrue(received.isEmpty())
      // The client started a handshake: 0x16 opens a TLS handshake record.
      assertEquals(0x16, server.clientDataBytes.firstOrNull()?.toInt())
    }
  }

  @Test
  fun upgradeToTls_protPRefused_throwsInsteadOfClearData() = runTest {
    val server = ScriptedFtpServer(
      content = content,
      sslContext = testCertificate.sslContext,
      protReply = "536 Requested PROT level not supported",
    )
    server.use {
      val client = client(server)
      try {
        client.connect()
        assertFailsWith<KetchError.SourceError> { client.upgradeToTls() }
      } finally {
        client.disconnect()
      }
      assertTrue(server.commands.none { it.startsWith("RETR") })
    }
  }

  /** A self-signed certificate for localhost, made with the JDK's keytool. */
  private class TestCertificate {
    val sslContext: SSLContext
    val trustManager: X509TrustManager

    init {
      val dir = createTempDirectory("ketch-ftps").toFile()
      val file = File(dir, "server.p12")
      val password = "changeit"
      val keytool = File(System.getProperty("java.home"), "bin/keytool").path
      val process = ProcessBuilder(
        keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
        "-storetype", "PKCS12", "-keystore", file.path,
        "-storepass", password, "-keypass", password, "-noprompt",
      ).redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().readText()
      check(process.waitFor() == 0) { "keytool failed: $output" }

      val keyStore = KeyStore.getInstance("PKCS12")
      file.inputStream().use { keyStore.load(it, password.toCharArray()) }
      dir.deleteRecursively()

      val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      keys.init(keyStore, password.toCharArray())
      sslContext = SSLContext.getInstance("TLS")
      sslContext.init(keys.keyManagers, null, null)

      val trusted = KeyStore.getInstance(KeyStore.getDefaultType())
      trusted.load(null, null)
      trusted.setCertificateEntry("server", keyStore.getCertificate("server") as X509Certificate)
      val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      trust.init(trusted)
      trustManager = trust.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
  }

  private companion object {
    val testCertificate by lazy { TestCertificate() }
  }
}
