package com.linroid.ketch.torrent

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Two uTP sockets over a simulated network, in virtual microseconds. */
class UtpSocketTest {
  /**
   * The path between the initiator (A) and the responder (B): one-way [delay] plus up to
   * [jitter], [loss] of the packets dropped, and A→B through a link of [bandwidth] bytes per
   * second whose queue grows as A sends faster.
   */
  private class Network(
    val delay: Long = 20_000,
    val jitter: Long = 0,
    val loss: Double = 0.0,
    val bandwidth: Long = 0,
    seed: Int = 1,
    /** Packets of these numbers, counted from 0 in sending order, are dropped. */
    val drop: Set<Int> = emptySet(),
    val responderSeq: Int = 1000,
    val options: UtpOptions = UtpOptions(),
  ) {
    private class Event(val at: Long, val toB: Boolean, val bytes: ByteArray, val order: Long)

    private val random = Random(seed)
    private val events = mutableListOf<Event>()
    private var order = 0L
    private var sent = 0
    private var linkFreeAt = 0L
    var now = 0L
      private set
    var maxQueueDelay = 0L
      private set
    var a: UtpSocket? = null
      private set
    var b: UtpSocket? = null
      private set
    /** Packets that reached B before it existed, other than SYNs, are answered with nothing. */
    var lostToB = 0
      private set

    fun connect(): UtpSocket = UtpSocket.connect(0x1234, { send(it, toB = true) }, now, options)
      .also { a = it }

    private fun send(packet: UtpPacket, toB: Boolean) {
      val index = sent++
      if (index in drop || random.nextDouble() < loss) return
      val bytes = packet.encode()
      var at = now + delay + if (jitter > 0) random.nextLong(jitter) else 0
      if (toB && bandwidth > 0) {
        val transmit = bytes.size * 1_000_000L / bandwidth
        val start = maxOf(now, linkFreeAt)
        linkFreeAt = start + transmit
        maxQueueDelay = maxOf(maxQueueDelay, start - now)
        at = linkFreeAt + delay
      }
      events += Event(at, toB, bytes, order++)
    }

    /** Runs until [done] or [limit] microseconds pass, ticking every 10 ms. */
    fun run(limit: Long, step: () -> Unit = {}, done: () -> Boolean) {
      val end = now + limit
      var nextTick = now
      while (now < end && !done()) {
        val event = events.minWithOrNull(compareBy<Event>({ it.at }, { it.order }))
        if (event != null && event.at <= nextTick) {
          events.remove(event)
          now = event.at
          deliver(event)
        } else {
          now = nextTick
          nextTick += 10_000
          a?.tick(now)
          b?.tick(now)
        }
        step()
      }
    }

    private fun deliver(event: Event) {
      val packet = assertNotNull(UtpPacket.decode(event.bytes))
      if (!event.toB) {
        a?.receive(packet, now)
        return
      }
      val responder = b
      if (responder == null) {
        if (packet.type == UtpType.SYN) {
          b = UtpSocket.accept(packet, { send(it, toB = false) }, now, options,
            initialSeq = responderSeq)
        } else {
          lostToB++
        }
      } else if (packet.connectionId == responder.receiveId ||
        packet.type == UtpType.SYN) {
        responder.receive(packet, now)
      }
    }
  }

  /** Writes [payload] from [from] as space allows and reads everything [to] gets. */
  private class Transfer(val payload: ByteArray) {
    var written = 0
    val read = mutableListOf<ByteArray>()
    val readBytes: Int get() = read.sumOf { it.size }

    fun step(from: UtpSocket?, to: UtpSocket?, now: Long) {
      if (from != null && from.currentState == UtpSocket.State.CONNECTED &&
        written < payload.size) {
        written += from.write(payload, written, payload.size - written, now)
      }
      if (to != null && to.available > 0) read += to.read(Int.MAX_VALUE, now)
    }

    fun received(): ByteArray = read.fold(ByteArray(0)) { all, chunk -> all + chunk }
  }

  private fun transfer(network: Network, size: Int, limit: Long = 120_000_000): Transfer {
    val data = Random(7).nextBytes(size)
    val transfer = Transfer(data)
    network.connect()
    network.run(limit, step = { transfer.step(network.a, network.b, network.now) }) {
      transfer.readBytes == size
    }
    assertEquals(size, transfer.readBytes, "Transferred by ${network.now / 1000} ms")
    assertContentEquals(data, transfer.received())
    return transfer
  }

  @Test
  fun lossless_transfersAndGrowsTheWindow() {
    val network = Network()
    transfer(network, 1_000_000)
    val a = assertNotNull(network.a)
    assertTrue(a.congestionWindow > 64 * 1024, "Window ${a.congestionWindow}")
    // Round trip of 40 ms, measured.
    assertTrue(a.roundTripMicros in 39_000..60_000, "RTT ${a.roundTripMicros}")
    // 1 MB at a growing window takes a few round trips, not seconds per packet.
    assertTrue(network.now < 3_000_000, "Took ${network.now / 1000} ms")
  }

  @Test
  fun lossAndReordering_stillDeliverEveryByteInOrder() {
    transfer(Network(jitter = 30_000, loss = 0.05, seed = 3), 300_000)
  }

  @Test
  fun heavyLoss_stillDelivers() {
    transfer(Network(loss = 0.2, seed = 11), 60_000)
  }

  @Test
  fun burstLoss_isRecoveredQuickly() {
    // 150 packets in a row lost mid-transfer, as a receiver's full UDP buffer drops them, while
    // later packets still arrive and keep acks coming.
    val network = Network(delay = 1_000, drop = (300 until 450).toSet())
    transfer(network, 1_500_000)
    assertTrue(network.now < 5_000_000, "Took ${network.now / 1000} ms")
  }

  @Test
  fun bothDirections_atOnce() {
    val network = Network(loss = 0.02, jitter = 5_000, seed = 5)
    val up = Transfer(Random(1).nextBytes(200_000))
    val down = Transfer(Random(2).nextBytes(150_000))
    network.connect()
    network.run(60_000_000, step = {
      up.step(network.a, network.b, network.now)
      down.step(network.b, network.a, network.now)
    }) { up.readBytes == up.payload.size && down.readBytes == down.payload.size }
    assertContentEquals(up.payload, up.received())
    assertContentEquals(down.payload, down.received())
  }

  @Test
  fun randomPaths_alwaysDeliverBothWays() {
    for (seed in 1..40) {
      val random = Random(seed)
      val network = Network(delay = 1_000 + random.nextLong(80_000),
        jitter = random.nextLong(60_000), loss = random.nextDouble() * 0.15, seed = seed,
        responderSeq = random.nextInt(0x10000))
      val up = Transfer(random.nextBytes(random.nextInt(1, 120_000)))
      val down = Transfer(random.nextBytes(random.nextInt(1, 120_000)))
      network.connect()
      network.run(300_000_000, step = {
        up.step(network.a, network.b, network.now)
        down.step(network.b, network.a, network.now)
      }) { up.readBytes == up.payload.size && down.readBytes == down.payload.size }
      assertContentEquals(up.payload, up.received(), "Seed $seed up")
      assertContentEquals(down.payload, down.received(), "Seed $seed down")
    }
  }

  @Test
  fun responderSequence_wrapsAroundZero() {
    val network = Network(responderSeq = 0xfff0)
    val down = Transfer(Random(4).nextBytes(100_000))
    network.connect()
    network.run(30_000_000, step = { down.step(network.b, network.a, network.now) }) {
      down.readBytes == down.payload.size
    }
    assertContentEquals(down.payload, down.received())
  }

  @Test
  fun lostSyn_isSentAgain() {
    val network = Network(drop = setOf(0))
    transfer(network, 10_000)
    // The second SYN waited for the first timeout.
    assertTrue(network.now >= 1_000_000)
  }

  @Test
  fun lostSynAnswer_isAnsweredAgain() {
    // Packet 1 is B's STATE answering the SYN.
    transfer(Network(drop = setOf(1)), 10_000)
  }

  @Test
  fun lostSynAnswer_dataBeforeTheStateIsNotTakenAsTheStart() {
    // B's STATE (packet 1) is lost and B sends data at once: A must not guess where B's stream
    // starts from whichever data arrives first, and waits for the STATE its next SYN draws.
    val network = Network(drop = setOf(1), jitter = 30_000, seed = 2)
    val down = Transfer(Random(8).nextBytes(20_000))
    network.connect()
    network.run(10_000_000, step = { down.step(network.b, network.a, network.now) }) {
      down.readBytes == down.payload.size
    }
    assertContentEquals(down.payload, down.received())
  }

  @Test
  fun reorderBuffer_countsUnreadBytesAgainstTheWindow() {
    val options = UtpOptions(receiveBuffer = 8 * 1200)
    val sent = mutableListOf<UtpPacket>()
    val syn = UtpPacket(UtpType.SYN, 7, 0, 0, 1_000_000, 1, 0)
    val socket = UtpSocket.accept(syn, { sent += it }, 0, options, initialSeq = 100)
    fun data(seq: Int) = UtpPacket(UtpType.DATA, 8, 0, 0, 1_000_000, seq, 99,
      payload = ByteArray(1200))
    // Six packets in order fill most of the buffer, unread.
    for (seq in 2..7) socket.receive(data(seq), 0)
    assertEquals(6L * 1200, socket.available)
    // Past a gap, only what is left of the window is held: two packets, not eight.
    for (seq in 9..16) socket.receive(data(seq), 0)
    val window = sent.last().window
    assertEquals(0L, window)
  }

  @Test
  fun silentPeer_failsAfterTheSynRetries() {
    val network = Network(loss = 1.0)
    val a = network.connect()
    network.run(120_000_000) { a.isFinished }
    assertEquals(UtpSocket.State.FAILED, a.currentState)
    assertEquals("No uTP answer", a.failure)
    // 1 + 2 + 4 + 8 + 16 seconds of backoff.
    assertTrue(network.now in 30_000_000..32_000_000, "Gave up at ${network.now / 1000} ms")
  }

  @Test
  fun reset_failsThePeer() {
    val network = Network()
    val a = network.connect()
    network.run(1_000_000) { network.b != null && a.currentState == UtpSocket.State.CONNECTED }
    assertNotNull(network.b).abort(network.now)
    network.run(1_000_000) { a.isFinished }
    assertEquals(UtpSocket.State.FAILED, a.currentState)
    assertEquals("Peer reset the connection", a.failure)
  }

  @Test
  fun close_deliversEverythingThenEndsTheStream() {
    val network = Network(loss = 0.05, seed = 9)
    val data = Random(3).nextBytes(50_000)
    val a = network.connect()
    var written = 0
    val read = mutableListOf<ByteArray>()
    network.run(60_000_000, step = {
      if (a.currentState == UtpSocket.State.CONNECTED && written < data.size) {
        written += a.write(data, written, data.size - written, network.now)
        if (written == data.size) a.close(network.now)
      }
      network.b?.let { if (it.available > 0) read += it.read(Int.MAX_VALUE, network.now) }
    }) { network.b?.isEndOfStream == true && a.isFinished }
    assertContentEquals(data, read.fold(ByteArray(0)) { all, chunk -> all + chunk })
    assertTrue(assertNotNull(network.b).isEndOfStream)
    assertEquals(UtpSocket.State.CLOSED, a.currentState)
  }

  @Test
  fun receiveWindow_holdsTheSenderUntilTheReaderCatchesUp() {
    val options = UtpOptions(receiveBuffer = 32 * 1024)
    val network = Network(options = options)
    val data = Random(6).nextBytes(200_000)
    val a = network.connect()
    var written = 0
    network.run(5_000_000, step = {
      if (a.currentState == UtpSocket.State.CONNECTED && written < data.size) {
        written += a.write(data, written, data.size - written, network.now)
      }
    }) { false }
    // Nobody read: B holds at most its buffer, and A stopped sending.
    val b = assertNotNull(network.b)
    assertTrue(b.available <= 32 * 1024, "Held ${b.available}")
    assertTrue(a.inFlight <= 32 * 1024 + options.maxPayload, "In flight ${a.inFlight}")
    val read = mutableListOf<ByteArray>()
    network.run(60_000_000, step = {
      if (written < data.size) written += a.write(data, written, data.size - written, network.now)
      if (b.available > 0) read += b.read(Int.MAX_VALUE, network.now)
    }) { read.sumOf { it.size } == data.size }
    assertContentEquals(data, read.fold(ByteArray(0)) { all, chunk -> all + chunk })
  }

  @Test
  fun ledbat_keepsTheBottleneckQueueNearItsTarget() {
    // 1 MB/s with 20 ms each way: without LEDBAT the queue would grow with the window.
    val network = Network(bandwidth = 1_000_000)
    val transfer = transfer(network, 4_000_000)
    assertTrue(transfer.readBytes == 4_000_000)
    // The queuing delay settles around the 100 ms target instead of growing unbounded.
    assertTrue(network.maxQueueDelay < 250_000, "Queue reached ${network.maxQueueDelay / 1000} ms")
    // And the link stayed busy: 4 MB at 1 MB/s takes a little over four seconds.
    assertTrue(network.now < 6_000_000, "Took ${network.now / 1000} ms")
  }
}
