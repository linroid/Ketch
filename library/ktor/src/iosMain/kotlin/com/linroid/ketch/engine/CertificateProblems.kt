package com.linroid.ketch.engine

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted

/** `NSURLSession` refuses a certificate with one of the `NSURLErrorServerCertificate` codes. */
internal actual fun certificateProblemOf(error: Throwable): CertificateProblem? {
  causesOf(error).forEach { cause ->
    if (cause !is DarwinHttpRequestException || cause.origin.domain != NSURLErrorDomain) {
      return@forEach
    }
    when (cause.origin.code) {
      NSURLErrorServerCertificateHasBadDate -> return CertificateProblem.OutsideValidity
      NSURLErrorServerCertificateNotYetValid -> return CertificateProblem.NotYetValid
      NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasUnknownRoot ->
        return CertificateProblem.Untrusted
    }
  }
  return null
}
