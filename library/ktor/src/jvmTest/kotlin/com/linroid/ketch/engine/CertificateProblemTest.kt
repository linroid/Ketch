package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchError
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CertificateProblemTest {
  @Test
  fun expiredCertificate_deepInTheChain_isExpired() {
    val error = SSLHandshakeException("(certificate_expired) PKIX path validation failed").apply {
      initCause(
        CertificateException(
          "validity check failed",
          CertPathValidatorException("validity check failed", CertificateExpiredException()),
        ),
      )
    }

    assertEquals(CertificateProblem.Expired, certificateProblemOf(error))
  }

  @Test
  fun validatorReason_expired_isExpired() {
    val reason = CertPathValidatorException.BasicReason.EXPIRED
    val error = SSLHandshakeException("PKIX path validation failed").apply {
      initCause(CertPathValidatorException("timestamp check failed", null, null, -1, reason))
    }

    assertEquals(CertificateProblem.Expired, certificateProblemOf(error))
  }

  @Test
  fun unknownIssuer_isUntrusted() {
    val error = SSLHandshakeException("PKIX path building failed").apply {
      initCause(CertificateException("unable to find valid certification path"))
    }

    assertEquals(CertificateProblem.Untrusted, certificateProblemOf(error))
  }

  @Test
  fun hostnameNotVerified_isWrongHost() {
    val error = SSLPeerUnverifiedException("Hostname wrong.example not verified")

    assertEquals(CertificateProblem.WrongHost, certificateProblemOf(error))
  }

  @Test
  fun handshakeCutShort_isNoCertificateProblem() {
    assertNull(certificateProblemOf(SSLHandshakeException("Remote host terminated the handshake")))
    assertNull(certificateProblemOf(SocketTimeoutException("Read timed out")))
  }

  @Test
  fun untrustedServer_failsAtOnceSayingWhy() = runTest {
    withContext(Dispatchers.IO) {
      val requests = AtomicInteger()
      val server = selfSignedServer()
      server.createContext("/file") { exchange ->
        exchange.use {
          requests.incrementAndGet()
          it.sendResponseHeaders(200, -1)
        }
      }
      server.start()
      val engine = KtorHttpEngine()
      try {
        val url = "https://127.0.0.1:${server.address.port}/file"

        val error = assertFailsWith<KetchError.SourceError> { engine.head(url, emptyMap()) }

        assertEquals("The server's certificate is not trusted", error.detail)
        assertFalse(error.isRetryable)
        assertEquals(0, requests.get())
      } finally {
        engine.close()
        server.stop(0)
      }
    }
  }

  /** An HTTPS server on loopback with a self-signed certificate, made with the JDK's keytool. */
  private fun selfSignedServer(): HttpsServer {
    val root = Files.createTempDirectory("ketch-certificate").toFile()
    val password = "fixture-only"
    val keyStoreFile = root.resolve("fixture.p12")
    val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
    val generated = ProcessBuilder(
      keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
      "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
      "-storetype", "PKCS12", "-keystore", keyStoreFile.absolutePath, "-storepass", password,
      "-noprompt",
    ).redirectErrorStream(true).redirectOutput(root.resolve("keytool.log")).start()
    check(generated.waitFor(30, TimeUnit.SECONDS) && generated.exitValue() == 0)
    val keys = KeyStore.getInstance("PKCS12").apply {
      keyStoreFile.inputStream().use { load(it, password.toCharArray()) }
    }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      .apply { init(keys, password.toCharArray()) }
    val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    return HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      httpsConfigurator = HttpsConfigurator(ssl)
    }
  }
}
