package com.linroid.ketch.hooks

import com.linroid.ketch.api.KetchApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Posts hook payloads to webhooks, retrying after [retryDelays] when a request fails on the way or
 * the receiver answers 408, 429 or 5xx. Redirects are never followed.
 */
internal class WebhookSender(
  client: HttpClient? = null,
  private val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
) : AutoCloseable {
  // Made on the first webhook, so a process with only command hooks never starts a client.
  private val lazyClient = lazy { client ?: defaultClient() }
  private val client by lazyClient

  override fun close() {
    if (lazyClient.isInitialized()) client.close()
  }

  /**
   * Posts [body], the payload of [event] with ID [deliveryId], to [url] with [headers], signed
   * with [secret] when set, giving each attempt [timeout].
   */
  suspend fun send(
    url: String,
    headers: Map<String, String>,
    secret: String?,
    event: String,
    deliveryId: String,
    body: String,
    timeout: Duration,
  ): HookOutcome {
    var last: HookOutcome.Failed
    var attempt = 0
    while (true) {
      val result = attempt(url, headers, secret, event, deliveryId, body, timeout)
      last = when (result) {
        Attempt.Delivered -> return HookOutcome.Done
        is Attempt.Refused -> return HookOutcome.Failed(result.reason)
        is Attempt.Retryable -> HookOutcome.Failed(result.reason)
      }
      val wait = retryDelays.getOrNull(attempt++) ?: return last
      delay(wait)
    }
  }

  private suspend fun attempt(
    url: String,
    headers: Map<String, String>,
    secret: String?,
    event: String,
    deliveryId: String,
    body: String,
    timeout: Duration,
  ): Attempt {
    val status = try {
      withTimeout(timeout) {
        client.post(url) {
          for ((name, value) in headers) header(name, value)
          header(HttpHeaders.UserAgent, "Ketch/${KetchApi.VERSION}")
          header(EVENT_HEADER, event)
          header(DELIVERY_HEADER, deliveryId)
          if (!secret.isNullOrEmpty()) header(SIGNATURE_HEADER, signature(secret, body))
          setBody(TextContent(body, ContentType.Application.Json))
        }.status.value
      }
    } catch (_: TimeoutCancellationException) {
      return Attempt.Retryable("timed out after $timeout")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Only the type: messages of client exceptions quote the URL, which can hold a token.
      return Attempt.Retryable("request failed (${e::class.simpleName})")
    }
    return when {
      status in 200..299 -> Attempt.Delivered
      status == 408 || status == 429 || status >= 500 -> Attempt.Retryable("answered $status")
      else -> Attempt.Refused("answered $status")
    }
  }

  private sealed interface Attempt {
    data object Delivered : Attempt
    data class Retryable(val reason: String) : Attempt
    data class Refused(val reason: String) : Attempt
  }

  companion object {
    /** Header naming the event, such as `completed`. */
    const val EVENT_HEADER: String = "X-Ketch-Event"

    /** Header with [HookPayload.deliveryId], the same on every attempt of a delivery. */
    const val DELIVERY_HEADER: String = "X-Ketch-Delivery"

    /** Header with `sha256=` and the hex HMAC-SHA256 of the body, keyed with the hook's secret. */
    const val SIGNATURE_HEADER: String = "X-Ketch-Signature"

    /** Waits before the second and the third attempt. */
    val DEFAULT_RETRY_DELAYS: List<Duration> = listOf(2.seconds, 10.seconds)

    /** The [SIGNATURE_HEADER] value of [body] for [secret]. */
    fun signature(secret: String, body: String): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
      return "sha256=" + mac.doFinal(body.toByteArray()).toHexString()
    }

    private fun defaultClient() = HttpClient(CIO) {
      followRedirects = false
      expectSuccess = false
    }
  }
}
