package com.linroid.ketch.core.engine

import com.linroid.ketch.api.ConnectionRoute
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What an engine learned about one hop of a request: where it was sent and how. [toString]
 * leaves [host] out, so an exchange can be logged.
 *
 * @property host the host the hop was sent to, IPv6 without brackets
 * @property port the port the hop was sent to
 * @property secure whether the hop used TLS
 * @property protocol the HTTP version that answered, such as `"HTTP/1.1"` or `"HTTP/2"`, or `null`
 *   when unknown
 * @property route how the hop reached [host]
 */
class HttpExchange(
  val host: String,
  val port: Int,
  val secure: Boolean,
  val protocol: String?,
  val route: ConnectionRoute,
) {
  override fun toString(): String =
    "HttpExchange(port=$port, secure=$secure, protocol=$protocol, route=$route)"
}

/**
 * A coroutine context element through which an [HttpEngine] reports where the requests of the
 * calling coroutine went. Callers install it with `withContext`; engines that support it call
 * [onExchange] for every hop of a request, the final one last, from any thread. Engines that do
 * not report leave callers with what they knew from the URL. The context passes through engines
 * that wrap another unchanged.
 */
class HttpExchangeObserver(val onExchange: (HttpExchange) -> Unit) :
  AbstractCoroutineContextElement(Key) {
  /** The key of [HttpExchangeObserver] in a coroutine context. */
  companion object Key : CoroutineContext.Key<HttpExchangeObserver>
}

/** This spec with the endpoint, security, version and route [exchange] reported. */
internal fun ConnectionSpec.with(exchange: HttpExchange): ConnectionSpec = copy(
  host = exchange.host,
  port = exchange.port,
  secure = exchange.secure,
  protocol = exchange.protocol ?: protocol,
  route = if (exchange.route == ConnectionRoute.UNKNOWN) route else exchange.route,
)

/**
 * Runs [block], whose requests describe [handle] by the hops the engine reports, starting from
 * [spec].
 */
internal suspend fun <T> observeExchanges(
  handle: ConnectionHandle,
  spec: ConnectionSpec,
  block: suspend () -> T,
): T {
  if (handle === ConnectionHandle.None) return block()
  return withContext(HttpExchangeObserver { handle.describe(spec.with(it)) }) { block() }
}
