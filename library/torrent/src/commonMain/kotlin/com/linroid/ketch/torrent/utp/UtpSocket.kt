package com.linroid.ketch.torrent.utp

import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import okio.Buffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Limits of one uTP connection; tests shrink them. */
internal class UtpOptions(
  /** Payload per packet, small enough to pass tunnels without fragmenting. */
  val maxPayload: Int = 1200,
  /** Bytes the application may queue before [UtpSocket.write] accepts less. */
  val sendBuffer: Int = 256 * 1024,
  /** Received bytes held, in order or not, before the window we advertise closes. */
  val receiveBuffer: Int = 1024 * 1024,
  /** The congestion window never grows past this. */
  val maxWindow: Int = 1024 * 1024,
  val initialTimeoutUs: Long = 1_000_000,
  val minTimeoutUs: Long = 500_000,
  /** SYN retransmissions before connecting fails. */
  val synRetries: Int = 4,
  /** Consecutive timeouts of a connected socket before it fails. */
  val maxTimeouts: Int = 7,
  /** A STATE goes out after this long without sending, keeping NAT mappings alive. */
  val keepAliveUs: Long = 29_000_000,
) {
  init { require(maxPayload in 64..1400 && sendBuffer > 0 && receiveBuffer >= maxPayload) }
}

/**
 * One uTP connection (BEP 29) as a deterministic state machine: no I/O, no coroutines and no
 * clock of its own. The caller hands it packets ([receive]), application bytes ([write],
 * [read]) and the time ([tick]); every packet to send goes to [send] at once. Sequencing,
 * selective acks, retransmission and LEDBAT congestion control live here, so a simulated
 * network can drive it exactly.
 */
internal class UtpSocket private constructor(
  /** The connection ID of packets we receive. */
  val receiveId: Int,
  /** The connection ID of packets we send. */
  val sendId: Int,
  private var state: State,
  private var seqNr: Int,
  private val send: (UtpPacket) -> Unit,
  private val options: UtpOptions,
  now: Long,
) {
  enum class State { SYN_SENT, CONNECTED, FIN_SENT, CLOSED, FAILED }

  /** One packet we sent that is not acknowledged yet. */
  private class Outgoing(
    val seq: Int,
    val type: UtpType,
    val payload: ByteArray,
    var sentAt: Long,
    var transmissions: Int = 1,
  )

  private val outgoing = ArrayDeque<Outgoing>()
  private var flightBytes = 0L
  private val pending = Buffer()
  private val received = Buffer()
  private val reorder = mutableMapOf<Int, UtpPacket>()
  private var reorderBytes = 0L
  private var ackNr = 0
  private var lastAckReceived = -1
  private var duplicateAcks = 0
  private var peerWindow = options.maxWindow.toLong()
  private var window = options.maxPayload.toDouble() * 2
  private var slowStart = true
  /** Losses cut the window once per flight: a later cut needs a loss after this sequence. */
  private var cutBefore = -1
  private var rtt = 0L
  private var rttVar = 0L
  private var timeout = options.initialTimeoutUs
  private var timeouts = 0
  private var lastProgress = now
  private var lastSent = now
  /** The delay of their packets to us, echoed to them as timestamp_difference. */
  private var replyMicros = 0
  private val delays = DelayHistory()
  private var ourDelay = 0L
  private var finSeq: Int? = null
  private var eofSeq: Int? = null
  private var closeRequested = false
  private var ackNeeded = false
  /** The sequence our STATE answering the SYN carried, answering a repeated SYN the same way. */
  private var synAnswerSeq = -1

  /** Why the connection failed, once [state] is [State.FAILED]. */
  var failure: String? = null
    private set

  val currentState: State get() = state

  /** Readable application bytes, in order. */
  val available: Long get() = received.size

  /** The peer finished sending and every byte it sent has been read. */
  val isEndOfStream: Boolean get() = received.size == 0L && eofSeq != null && ackNr == eofSeq

  /** Bytes [write] would accept now. */
  val writable: Int get() = (options.sendBuffer - pending.size).toInt().coerceAtLeast(0)

  /** Bytes sent and not acknowledged. */
  val inFlight: Long get() = flightBytes

  val congestionWindow: Int get() = window.toInt()

  val roundTripMicros: Long get() = rtt

  /** One line of internal state, for logs explaining a stalled connection. */
  fun describe(): String = "state=$state inFlight=$flightBytes packets=${outgoing.size} " +
    "window=${window.toInt()} peerWindow=$peerWindow pending=${pending.size} " +
    "available=${received.size} held=${reorder.size} rtt=${rtt / 1000}ms " +
    "timeout=${timeout / 1000}ms timeouts=$timeouts ack=$ackNr seq=$seqNr"

  /** No more traffic: closed, failed, or every byte acknowledged after both sides finished. */
  val isFinished: Boolean get() = state == State.CLOSED || state == State.FAILED

  /** Queues up to [writable] bytes of [bytes] from [offset] and returns how many. */
  fun write(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset, now: Long): Int {
    check(!closeRequested && state != State.CLOSED && state != State.FAILED) { "uTP socket closed" }
    val accepted = min(count, writable)
    pending.write(bytes, offset, accepted)
    flush(now)
    return accepted
  }

  /** Takes up to [max] readable bytes; reading reopens the window we advertise. */
  fun read(max: Int, now: Long): ByteArray {
    val wasClosed = advertisedWindow() < options.maxPayload
    val bytes = received.readByteArray(min(max.toLong(), received.size))
    // A window that reopens is announced, or a peer that saw it closed would wait for a timeout.
    if (wasClosed && advertisedWindow() >= options.maxPayload && state == State.CONNECTED) {
      sendState(now)
    }
    return bytes
  }

  /** Sends the queued bytes, then a FIN; the socket closes once the FIN is acknowledged. */
  fun close(now: Long) {
    if (closeRequested || state == State.CLOSED || state == State.FAILED) return
    closeRequested = true
    if (state == State.SYN_SENT) {
      state = State.CLOSED
      return
    }
    flush(now)
  }

  /** Ends the connection at once, telling the peer with a RESET. */
  fun abort(now: Long) {
    if (state == State.CLOSED || state == State.FAILED) return
    if (state != State.SYN_SENT) emit(UtpType.RESET, seqNr, null, now)
    state = State.CLOSED
  }

  fun receive(packet: UtpPacket, now: Long) {
    if (state == State.CLOSED || state == State.FAILED) return
    replyMicros = (now.toInt() - packet.timestamp)
    peerWindow = packet.window
    when (packet.type) {
      UtpType.RESET -> {
        fail("Peer reset the connection")
        return
      }
      UtpType.SYN -> {
        // Our STATE answering it was lost: answer again with the sequence our stream starts at,
        // though data may have gone out since, or the peer would skip that data.
        if (packet.seq == ackNr && synAnswerSeq != -1) emit(UtpType.STATE, synAnswerSeq, null, now)
        return
      }
      // Anything else means the peer connected: a later SYN is a stale duplicate.
      else -> synAnswerSeq = -1
    }
    if (state == State.SYN_SENT) {
      // Only the STATE acknowledging our SYN connects: it carries the sequence the responder's
      // first packet will use, as in libtorrent and libutp. Data arriving first cannot tell
      // where the stream starts, so it waits for the STATE our next SYN draws.
      if (packet.type != UtpType.STATE || packet.ack != utpSeq(seqNr, -1)) return
      ackNr = utpSeq(packet.seq, -1)
      state = State.CONNECTED
    }
    acknowledge(packet, now)
    if (packet.type == UtpType.DATA || packet.type == UtpType.FIN) accept(packet)
    if (ackNeeded) sendState(now)
    flush(now)
  }

  /** Retransmits on timeout, keeps the mapping alive and closes; call often (every 100 ms). */
  fun tick(now: Long) {
    if (state == State.CLOSED || state == State.FAILED) return
    if (outgoing.isNotEmpty() && now - lastProgress >= timeout) {
      timeouts++
      val limit = if (state == State.SYN_SENT) options.synRetries else options.maxTimeouts
      if (timeouts > limit) {
        if (state == State.FIN_SENT) state = State.CLOSED
        else fail(if (state == State.SYN_SENT) "No uTP answer" else "uTP peer stopped answering")
        return
      }
      // A timeout means the path lost everything in flight: start again from one packet.
      window = options.maxPayload.toDouble()
      slowStart = false
      timeout = min(timeout * 2, 60_000_000)
      lastProgress = now
      retransmit(outgoing.first(), now)
    }
    if (state != State.SYN_SENT) resendExpired(now)
    if (state == State.CONNECTED && now - lastSent >= options.keepAliveUs) sendState(now)
    flush(now)
  }

  /**
   * Sends again, oldest first and at most [MAX_RESENDS_PER_TICK] a tick, the packets still
   * unacknowledged a timeout after they last went out. Acks for later packets keep the
   * connection's own timeout from firing when a burst is lost, and fast retransmit repairs one
   * packet at a time, so without this a lost burst, or a lost retransmission, crawls.
   */
  private fun resendExpired(now: Long) {
    var resent = 0
    for (packet in outgoing) {
      if (resent == MAX_RESENDS_PER_TICK) break
      if (now - packet.sentAt < timeout) continue
      if (resent == 0) onLoss(packet.seq)
      retransmit(packet, now)
      resent++
    }
  }

  /** Sends what the windows allow: queued data, then the FIN once nothing is queued. */
  private fun flush(now: Long) {
    if (state != State.CONNECTED) return
    while (pending.size > 0) {
      val size = min(pending.size, options.maxPayload.toLong()).toInt()
      if (!canSend(size, now)) break
      sendData(UtpType.DATA, pending.readByteArray(size.toLong()), now)
    }
    if (closeRequested && pending.size == 0L && finSeq == null) {
      finSeq = seqNr
      sendData(UtpType.FIN, ByteArray(0), now)
      state = State.FIN_SENT
    }
  }

  private fun canSend(size: Int, now: Long): Boolean {
    if (flightBytes + size <= min(window.toLong(), peerWindow)) return true
    // Our own window always lets one packet out; a window the peer closed is probed with one
    // packet a timeout after the last progress, or it never tells us it opened.
    return flightBytes == 0L && (size <= peerWindow || now - lastProgress >= timeout)
  }

  private fun sendData(type: UtpType, payload: ByteArray, now: Long) {
    val seq = seqNr
    seqNr = utpSeq(seqNr)
    if (outgoing.isEmpty()) lastProgress = now
    outgoing.addLast(Outgoing(seq, type, payload, now))
    flightBytes += payload.size
    emit(type, seq, payload, now)
  }

  private fun retransmit(packet: Outgoing, now: Long) {
    packet.transmissions++
    packet.sentAt = now
    emit(packet.type, packet.seq, packet.payload, now)
  }

  private fun sendState(now: Long) = emit(UtpType.STATE, seqNr, null, now)

  private fun emit(type: UtpType, seq: Int, payload: ByteArray?, now: Long) {
    // SYN goes out on the receive ID; everything after on the send ID.
    val id = if (type == UtpType.SYN) receiveId else sendId
    send(UtpPacket(type, id, now.toInt(), replyMicros, advertisedWindow(), seq, ackNr,
      selectiveAck(), payload ?: EMPTY))
    lastSent = now
    ackNeeded = false
  }

  private fun advertisedWindow(): Long =
    (options.receiveBuffer - received.size - reorderBytes).coerceAtLeast(0)

  /** The bitmask of packets held past a gap, relative to the next packet we wait for. */
  private fun selectiveAck(): ByteArray? {
    if (reorder.isEmpty()) return null
    // Bit i stands for ackNr + 2 + i; held packets are at least two past ackNr.
    val indices = reorder.keys.map { ((it - ackNr) and 0xffff) - 2 }
    val bits = ByteArray(min(MAX_SACK_BYTES, (indices.max() / 32 + 1) * 4))
    for (index in indices) {
      if (index < bits.size * 8) {
        bits[index / 8] = (bits[index / 8].toInt() or (1 shl (index % 8))).toByte()
      }
    }
    return bits
  }

  /** Handles what [packet] acknowledges: cumulative ack, selective acks and delay samples. */
  private fun acknowledge(packet: UtpPacket, now: Long) {
    var ackedBytes = 0L
    var progressed = false
    while (outgoing.isNotEmpty() && !utpAfter(outgoing.first().seq, packet.ack)) {
      val done = outgoing.removeFirst()
      flightBytes -= done.payload.size
      ackedBytes += done.payload.size
      progressed = true
      // Karn: only packets sent once measure the round trip.
      if (done.transmissions == 1) sampleRtt(now - done.sentAt)
    }
    val sack = packet.selectiveAck
    if (sack != null) {
      val sacked = mutableSetOf<Int>()
      for (index in 0 until sack.size * 8) {
        if (sack[index / 8].toInt() and (1 shl (index % 8)) != 0) {
          sacked += utpSeq(packet.ack, index + 2)
        }
      }
      val iterator = outgoing.iterator()
      while (iterator.hasNext()) {
        val done = iterator.next()
        if (done.seq in sacked) {
          flightBytes -= done.payload.size
          ackedBytes += done.payload.size
          progressed = true
          if (done.transmissions == 1) sampleRtt(now - done.sentAt)
          iterator.remove()
        }
      }
      // A packet three later packets overtook is lost.
      for (lost in outgoing) {
        val overtaken = sacked.count { utpAfter(it, lost.seq) }
        if (overtaken >= 3 && now - lost.sentAt >= rtt) {
          retransmit(lost, now)
          onLoss(lost.seq)
        }
      }
    }
    if (packet.timestampDiff != 0) ourDelay = delays.sample(packet.timestampDiff, now)
    if (progressed) {
      timeouts = 0
      timeout = max(rtt + 4 * rttVar, options.minTimeoutUs)
      lastProgress = now
      grow(ackedBytes)
    }
    if (packet.ack == lastAckReceived && packet.type == UtpType.STATE && outgoing.isNotEmpty() &&
      !progressed) {
      // Three duplicate acks: the packet after the ack is lost.
      if (++duplicateAcks == 3) {
        val lost = outgoing.first()
        retransmit(lost, now)
        onLoss(lost.seq)
      }
    } else if (packet.ack != lastAckReceived) {
      duplicateAcks = 0
    }
    lastAckReceived = packet.ack
    // Done once everything up to and including our FIN is acknowledged, however it was.
    if (state == State.FIN_SENT && outgoing.isEmpty()) state = State.CLOSED
  }

  /** LEDBAT: grow towards 100 ms of queuing delay, shrink past it. */
  private fun grow(ackedBytes: Long) {
    if (ackedBytes == 0L) return
    if (slowStart && ourDelay < TARGET_DELAY / 2) {
      window += ackedBytes
    } else {
      slowStart = false
      val delayFactor = (TARGET_DELAY - ourDelay).toDouble() / TARGET_DELAY
      val windowFactor = min(ackedBytes.toDouble(), window) / window
      window += MAX_GAIN_PER_RTT * delayFactor * windowFactor
    }
    window = window.coerceIn(options.maxPayload.toDouble(), options.maxWindow.toDouble())
  }

  private fun onLoss(seq: Int) {
    if (cutBefore != -1 && !utpAfter(seq, cutBefore)) return
    slowStart = false
    window = max(options.maxPayload.toDouble(), window / 2)
    cutBefore = utpSeq(seqNr, -1)
  }

  private fun sampleRtt(sample: Long) {
    if (rtt == 0L) {
      rtt = sample
      rttVar = sample / 2
    } else {
      rttVar += (abs(rtt - sample) - rttVar) / 4
      rtt += (sample - rtt) / 8
    }
  }

  /** Delivers [packet] in order, or holds it until the gap before it fills. */
  private fun accept(packet: UtpPacket) {
    ackNeeded = true
    val distance = (packet.seq - ackNr) and 0xffff
    if (distance == 0 || distance >= 0x8000) return
    if (distance > 1) {
      // Only what the window we advertised allows is held, unread bytes included.
      if (distance <= MAX_REORDER && packet.seq !in reorder &&
        packet.payload.size <= advertisedWindow()) {
        reorder[packet.seq] = packet
        reorderBytes += packet.payload.size
      }
      return
    }
    // Past the window we advertised, as a zero-window probe is: dropped, and the ack carries
    // the window again.
    if (packet.payload.size > advertisedWindow()) return
    deliver(packet)
    while (true) {
      val next = reorder.remove(utpSeq(ackNr)) ?: break
      reorderBytes -= next.payload.size
      deliver(next)
    }
  }

  private fun deliver(packet: UtpPacket) {
    ackNr = packet.seq
    if (packet.type == UtpType.FIN) {
      eofSeq = packet.seq
      // Nothing after a FIN counts.
      reorder.clear()
      reorderBytes = 0
    } else if (eofSeq == null) {
      received.write(packet.payload)
    }
  }

  private fun fail(reason: String) {
    failure = reason
    state = State.FAILED
  }

  /**
   * The lowest one-way delay seen over the last two minutes, the base the current delay is
   * measured against; samples are 32-bit microsecond clocks of different machines, so only
   * their differences mean anything.
   */
  private class DelayHistory {
    private var current: Int? = null
    private var previous: Int? = null
    private var bucketStart = Long.MIN_VALUE

    /** Records [sample] and returns its delay above the base. */
    fun sample(sample: Int, now: Long): Long {
      if (bucketStart == Long.MIN_VALUE || now - bucketStart >= 60_000_000) {
        previous = current
        current = null
        bucketStart = now
      }
      current = lower(current, sample)
      val base = lower(previous, current!!)
      return (sample - base).toLong() and 0xffffffffL
    }

    private fun lower(a: Int?, b: Int): Int = if (a == null || b - a < 0) b else a
  }

  companion object {
    private const val TARGET_DELAY = 100_000L
    private const val MAX_GAIN_PER_RTT = 3000.0
    /** Packets held past a gap, at most. */
    private const val MAX_REORDER = 2048
    private const val MAX_SACK_BYTES = 252
    private const val MAX_RESENDS_PER_TICK = 16
    private val EMPTY = ByteArray(0)

    /** Starts connecting: [receiveId] is ours, the peer sends to it. The SYN goes out now. */
    fun connect(
      receiveId: Int,
      send: (UtpPacket) -> Unit,
      now: Long,
      options: UtpOptions = UtpOptions(),
    ): UtpSocket {
      require(receiveId in 0..0xffff)
      val socket = UtpSocket(receiveId, utpSeq(receiveId), State.SYN_SENT, 1, send, options, now)
      socket.sendData(UtpType.SYN, EMPTY, now)
      return socket
    }

    /** Answers [syn], connecting at once; the STATE answering it goes out now. */
    fun accept(
      syn: UtpPacket,
      send: (UtpPacket) -> Unit,
      now: Long,
      options: UtpOptions = UtpOptions(),
      initialSeq: Int = torrentRandomBytes(2).let {
        ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255)
      },
    ): UtpSocket {
      require(syn.type == UtpType.SYN)
      val socket = UtpSocket(utpSeq(syn.connectionId), syn.connectionId, State.CONNECTED,
        initialSeq, send, options, now)
      socket.ackNr = syn.seq
      socket.replyMicros = now.toInt() - syn.timestamp
      socket.peerWindow = syn.window
      socket.synAnswerSeq = initialSeq
      socket.sendState(now)
      return socket
    }
  }
}
