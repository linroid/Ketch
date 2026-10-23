package com.linroid.ketch.engine

import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertificateRevokedException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * JSSE and Conscrypt refuse a certificate with an `SSLHandshakeException` caused by a
 * [CertificateException] or [CertPathValidatorException], whose own causes may say it expired;
 * OkHttp refuses one for another host with an [SSLPeerUnverifiedException]
 * (`Hostname example.com not verified`).
 */
internal actual fun certificateProblemOf(error: Throwable): CertificateProblem? {
  val causes = causesOf(error)
  causes.forEach { cause ->
    when (cause) {
      is CertificateExpiredException -> return CertificateProblem.Expired
      is CertificateNotYetValidException -> return CertificateProblem.NotYetValid
      is CertificateRevokedException -> return CertificateProblem.Revoked
      is CertPathValidatorException -> when (cause.reason) {
        CertPathValidatorException.BasicReason.EXPIRED -> return CertificateProblem.Expired
        CertPathValidatorException.BasicReason.NOT_YET_VALID ->
          return CertificateProblem.NotYetValid
        CertPathValidatorException.BasicReason.REVOKED -> return CertificateProblem.Revoked
        else -> Unit
      }
      else -> Unit
    }
  }
  val wrongHost = causes.any {
    it is SSLPeerUnverifiedException && it.message.orEmpty().startsWith("Hostname ")
  }
  return when {
    wrongHost -> CertificateProblem.WrongHost
    causes.any { it is CertificateException || it is CertPathValidatorException } ->
      CertificateProblem.Untrusted
    else -> null
  }
}
