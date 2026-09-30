package com.linroid.ketch.ai.fetch

import okhttp3.Dns
import java.net.InetAddress

/**
 * OkHttp [Dns] that returns a host's addresses only if they pass
 * [UrlValidator.resolvePublicAddresses].
 *
 * [SafeFetcher] validates each URL before requesting it, but the client
 * looks the host up again when it connects. Resolving through the
 * validator here means the connection can only go to addresses that
 * passed the check at connect time, so a host cannot answer validation
 * with a public address and the connection with a private one (DNS
 * rebinding). IP literals never reach a [Dns]; [UrlValidator.validate]
 * checks those, and they cannot change between the two steps.
 */
internal class ValidatingDns(private val urlValidator: UrlValidator) : Dns {

  override fun lookup(hostname: String): List<InetAddress> =
    urlValidator.resolvePublicAddresses(hostname)
}
