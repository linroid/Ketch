package com.linroid.ketch.server

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.NoDownloadSourceException
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.resolve
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.remote.RemoteKetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertIsNot

/** What a client reads from a server that refuses to resolve an input, also through another. */
class RemoteResolveTest {

  /** Takes every `lack:` URL but lacks something for it; the rest of the URL is why. */
  private class LackingSource : DownloadSource<Unit> {
    override val type = "lacking"
    override val stateSerializer = Unit.serializer()
    override fun canHandle(input: ResolveInput): Boolean =
      input is ResolveInput.Url && input.url.startsWith("lack:")
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
      throw KetchError.Unsupported(IllegalStateException((input as ResolveInput.Url).url.drop(5)))
    override suspend fun download(context: DownloadContext, state: Unit?) = Unit
  }

  @Test
  fun resolve_refusedUrl_keepsWhetherNoSourceTakesItThroughEveryServer() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val ketch = Ketch(NoOpHttpEngine(), additionalSources = listOf(LackingSource()))
        val server = KetchServer(ketch, port = 0, mdnsEnabled = false)
        var upstream: RemoteKetch? = null
        var standIn: KetchServer? = null
        var client: RemoteKetch? = null
        try {
          server.start(wait = false)
          upstream = RemoteKetch("127.0.0.1", server.port())
          // Serves a client of the first server, as the desktop app does while `ketch server`
          // runs the downloads.
          standIn = KetchServer(upstream, port = 0, mdnsEnabled = false)
          standIn.start(wait = false)
          client = RemoteKetch("127.0.0.1", standIn.port())
          for (remote in listOf(upstream, client)) {
            val none = assertFailsWith<KetchError.Unsupported> {
              remote.resolve("gopher://example.com/a.zip")
            }
            assertIs<NoDownloadSourceException>(none.cause)
            val proxy = assertFailsWith<KetchError.Unsupported> {
              remote.resolve("lack:https_proxy is not an HTTP or SOCKS5 proxy URL")
            }
            assertIsNot<NoDownloadSourceException>(proxy.cause)
            assertEquals("https_proxy is not an HTTP or SOCKS5 proxy URL", proxy.cause?.message)
          }
        } finally {
          client?.close()
          standIn?.stop()
          upstream?.close()
          server.stop()
          ketch.close()
        }
      }
    }
  }
}
