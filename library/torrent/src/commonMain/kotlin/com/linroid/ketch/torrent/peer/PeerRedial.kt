package com.linroid.ketch.torrent.peer

/**
 * When a swarm that downloads dials an endpoint again after a dial failed or a connection
 * closed: 5, 15 and 30 s after the first three dials in a row that brought no payload, then one,
 * two and five minutes, and every five minutes from then on.
 *
 * An endpoint that never brought payload is retried [RETRIES] times, then given up until some
 * source finds it again later or a stalled swarm revives it: most endpoints trackers and the DHT
 * name are long gone. One that brought payload is never given up while the swarm downloads,
 * as a peer that drops often comes back: its dials start over at the first delay, and a source
 * naming it again has it dialed once [FOUND_AGAIN_MS] have passed since its last dial.
 */
internal object PeerRedial {
  /** How often an endpoint that never brought payload is dialed again before it is given up. */
  const val RETRIES = 3

  /** How soon after its last dial a peer that brought payload is dialed when found again. */
  const val FOUND_AGAIN_MS = 30_000L

  private val DELAYS_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 300_000)

  /**
   * How long to wait before dialing again after [attempts] dials in a row that brought no
   * payload, or null to give the endpoint up; [delivered] says whether it ever brought any.
   */
  fun delayMs(attempts: Int, delivered: Boolean): Long? {
    if (!delivered && attempts > RETRIES) return null
    return DELAYS_MS[(attempts - 1).coerceIn(0, DELAYS_MS.lastIndex)]
  }
}
