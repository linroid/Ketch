package com.linroid.ketch.torrent.peer

/**
 * When a swarm that downloads dials an endpoint again after a dial failed or a connection
 * closed: 5, 15 and 30 s after the first three dials in a row that brought no payload, then one,
 * two and five minutes, and every five minutes from then on.
 *
 * An endpoint that never brought payload is retried [RETRIES] times, then given up until some
 * source finds it again later or a stalled swarm revives it: most endpoints trackers and the DHT
 * name are long gone. One that brought payload is retried [DELIVERED_RETRIES] times, about half
 * an hour, as a peer that drops often comes back; then it is given up as any other, so peers
 * gone for good never fill the swarm's book or its dials. A connection that brings payload, or
 * stays up [LASTING_MS], counts as the first of its endpoint's dials when it closes. A source
 * naming a peer that brought payload again has it dialed once [FOUND_AGAIN_MS] have passed since
 * its last dial, at most once every [FOUND_AGAIN_EVERY_MS]: the DHT names the same peers every
 * minute. A source naming it while it is being dialed counts once that dial fails, as a peer that
 * comes back during a dial announces itself before the dial gives up.
 */
internal object PeerRedial {
  /** How often an endpoint that never brought payload is dialed again before it is given up. */
  const val RETRIES = 3

  /** How often an endpoint that brought payload is dialed again before it is given up. */
  const val DELIVERED_RETRIES = 10

  /** How soon after its last dial a peer that brought payload is dialed when found again. */
  const val FOUND_AGAIN_MS = 30_000L

  /** How often at most a source naming a peer again has it dialed sooner. */
  const val FOUND_AGAIN_EVERY_MS = 300_000L

  /** A connection that stayed up this long counts as the first of its endpoint's dials. */
  const val LASTING_MS = 300_000L

  private val DELAYS_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 300_000)

  /**
   * How long to wait before dialing again after [attempts] dials in a row that brought no
   * payload, or null to give the endpoint up; [delivered] says whether it brought any since it
   * was last given up.
   */
  fun delayMs(attempts: Int, delivered: Boolean): Long? {
    if (spent(attempts, delivered)) return null
    return DELAYS_MS[(attempts - 1).coerceIn(0, DELAYS_MS.lastIndex)]
  }

  /**
   * Whether [attempts] dials in a row that brought no payload spent an endpoint's retries, so it
   * is given up. A revive dials such an endpoint once more, which leaves when it was given up:
   * a stall reviving every minute never keeps it from being forgotten.
   */
  fun spent(attempts: Int, delivered: Boolean): Boolean =
    attempts > if (delivered) DELIVERED_RETRIES else RETRIES

  /**
   * When to dial a peer that brought payload and waits to be dialed at [retryAt], now that a
   * source names it again: [FOUND_AGAIN_MS] after its last dial at [dialedAt]. Null leaves it
   * waiting: that would be no sooner, or a source brought it forward at [broughtForwardAt] less
   * than [FOUND_AGAIN_EVERY_MS] ago.
   */
  fun foundAgainAt(now: Long, dialedAt: Long, retryAt: Long, broughtForwardAt: Long?): Long? {
    if (broughtForwardAt != null && now - broughtForwardAt < FOUND_AGAIN_EVERY_MS) return null
    return (dialedAt + FOUND_AGAIN_MS).takeIf { it < retryAt }
  }
}
