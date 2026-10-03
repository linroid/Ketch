package com.linroid.ketch.app.ui.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.MdnsDiscoverer
import com.linroid.ketch.app.instance.createMdnsDiscoverer
import com.linroid.ketch.config.ServerConfig
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.connect_nearby_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Looks for Ketch devices announced on the local network, for the Add device sheet.
 *
 * A search browses in a few short rounds rather than one long one, so the devices that answer
 * quickly show within seconds while the rest keep coming. Devices stay listed in the order they
 * were found.
 *
 * @param rounds how long each round browses.
 * @param dispatcher where the browsing runs.
 */
@Stable
internal class NearbySearch(
  private val discoverer: MdnsDiscoverer,
  private val scope: CoroutineScope,
  private val rounds: List<Duration> = ROUNDS,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  private val log = KetchLogger("NearbySearch")

  /** Whether this platform can search the network; "Find on network" hides without. */
  val supported: Boolean get() = discoverer.supported

  /** Devices found so far, in the order they were found. */
  var servers by mutableStateOf<List<DiscoveredServer>>(emptyList())
    private set

  /** Whether a search is under way. */
  var searching by mutableStateOf(false)
    private set

  /** Whether a search has run to its end. */
  var searched by mutableStateOf(false)
    private set

  /** Why the last search failed, or `null`. */
  var error by mutableStateOf<UiText?>(null)
    private set

  /** Starts a search, unless one is under way; devices found before stay listed. */
  fun search() {
    if (searching || !supported) return
    searching = true
    error = null
    scope.launch {
      try {
        for (round in rounds) {
          val found = withContext(dispatcher) {
            discoverer.discover(ServerConfig.MDNS_SERVICE_TYPE, round.inWholeMilliseconds)
          }
          servers = merge(servers, found)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't search the network: ${e.describeCauses()}" }
        error = Res.string.connect_nearby_failed.text()
      } finally {
        searching = false
        searched = true
      }
    }
  }

  // What a round found replaces what an earlier one did; new devices go to the end.
  private fun merge(
    known: List<DiscoveredServer>,
    found: List<DiscoveredServer>,
  ): List<DiscoveredServer> {
    val fresh = found.associateBy(::addressOf)
    val kept = known.map { fresh[addressOf(it)] ?: it }
    val keys = kept.mapTo(HashSet(), ::addressOf)
    return kept + fresh.values.filter { addressOf(it) !in keys }
  }

  private fun addressOf(server: DiscoveredServer): String = "${server.host}:${server.port}"

  private companion object {
    val ROUNDS = listOf(3.seconds, 4.seconds, 5.seconds)
  }
}

/** A [NearbySearch] of this platform's network that stops when it leaves the composition. */
@Composable
internal fun rememberNearbySearch(): NearbySearch {
  val scope = rememberCoroutineScope()
  return remember(scope) { NearbySearch(createMdnsDiscoverer(), scope) }
}
