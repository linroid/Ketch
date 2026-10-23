package com.linroid.ketch.engine

/**
 * Why a server's certificate was refused, in words safe to show: the same certificate is refused
 * on every attempt, so the engine reports it as a [com.linroid.ketch.api.KetchError.SourceError]
 * with this [description] rather than as a network failure, which downloads retry.
 */
internal enum class CertificateProblem(val description: String) {
  Expired("The server's certificate has expired"),
  NotYetValid("The server's certificate is not valid yet"),
  OutsideValidity("The server's certificate has expired or is not valid yet"),
  Revoked("The server's certificate has been revoked"),
  WrongHost("The server's certificate is for another host"),
  Untrusted("The server's certificate is not trusted"),
}

/** The [CertificateProblem] [error] or one of its causes reports, `null` for other failures. */
internal expect fun certificateProblemOf(error: Throwable): CertificateProblem?

/** [error] and its causes, at most [MAX_CAUSES] of them, stopping at a cycle. */
internal fun causesOf(error: Throwable): List<Throwable> {
  val causes = mutableListOf<Throwable>()
  var current: Throwable? = error
  while (current != null && causes.size < MAX_CAUSES && causes.none { it === current }) {
    causes += current
    current = current.cause
  }
  return causes
}

private const val MAX_CAUSES = 16
