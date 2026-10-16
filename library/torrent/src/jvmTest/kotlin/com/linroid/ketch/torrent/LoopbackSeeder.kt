package com.linroid.ketch.torrent

import com.linroid.ketch.torrent.metainfo.Bencode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random

/**
 * A v1 seed of [payload] for throughput measurements: it serves blocks from memory on plain
 * threads, so its work stays apart from the engine's dispatchers and can be subtracted. Each
 * request is answered [delayMs] after it arrives, which a downloader sees as that round trip.
 * It unchokes a peer once interested, chokes it when no longer, and advertises [requestQueue].
 */
internal class LoopbackSeeder(
  private val payload: ByteArray,
  private val length: Int,
  private val pieceLength: Int,
  private val infoHash: ByteArray,
  private val delayMs: Long,
  private val requestQueue: Int = 500,
) : AutoCloseable {
  private val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
  private val sockets = CopyOnWriteArrayList<Socket>()
  private val pieces = (length + pieceLength - 1) / pieceLength
  private val peerId = Random.nextBytes(20)
  private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

  /** Every thread the seeder ran, with what each spent once it ended. */
  private val workers = CopyOnWriteArrayList<Worker>()
  @Volatile private var closed = false

  val port: Int get() = server.localPort

  init {
    spawn("seeder-accept-$port") {
      while (!closed) {
        val socket = try {
          server.accept()
        } catch (_: IOException) {
          break
        }
        sockets += socket
        spawn("seeder-$port-${socket.port}") { serve(socket) }
      }
    }
  }

  /** A seeder thread; its totals are kept once it ends, when the JVM no longer reports them. */
  private class Worker {
    lateinit var thread: Thread
    @Volatile var cpuNanos = 0L
    @Volatile var allocatedBytes = 0L
    @Volatile var done = false
  }

  /** CPU time and heap allocation, in nanoseconds and bytes. */
  class Usage(val cpuNanos: Long, val allocatedBytes: Long)

  /** What every thread the seeder ran has spent so far, ended ones included. */
  fun usage(): Usage {
    var cpu = 0L
    var allocated = 0L
    for (worker in workers) {
      if (!worker.done) {
        @Suppress("DEPRECATION")
        val id = worker.thread.id
        val liveCpu = threads.getThreadCpuTime(id)
        val liveAllocated = threads.getThreadAllocatedBytes(id)
        // -1 once the thread ended, and by then it stored its totals.
        if (liveCpu >= 0 && liveAllocated >= 0) {
          cpu += liveCpu
          allocated += liveAllocated
          continue
        }
      }
      cpu += worker.cpuNanos
      allocated += worker.allocatedBytes
    }
    return Usage(cpu, allocated)
  }

  private fun spawn(name: String, body: () -> Unit) {
    val worker = Worker()
    worker.thread = Thread({
      try {
        body()
      } finally {
        worker.cpuNanos = threads.currentThreadCpuTime
        worker.allocatedBytes = threads.currentThreadAllocatedBytes
        worker.done = true
      }
    }, name).apply { isDaemon = true }
    workers += worker
    worker.thread.start()
  }

  private sealed interface Out {
    data class Control(val id: Int) : Out
    data class Block(val index: Int, val begin: Int, val length: Int, val due: Long) : Out
  }

  private fun serve(socket: Socket) {
    try {
      socket.tcpNoDelay = true
      val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
      val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))
      require(input.readUnsignedByte() == 19)
      val header = ByteArray(19 + 8 + 20 + 20)
      input.readFully(header)
      require(header.copyOfRange(27, 47).contentEquals(infoHash)) { "Unknown torrent" }
      val extensions = header[19 + 5].toInt() and 0x10 != 0
      output.write(19)
      output.write("BitTorrent protocol".encodeToByteArray())
      output.write(byteArrayOf(0, 0, 0, 0, 0, if (extensions) 0x10 else 0, 0, 0))
      output.write(infoHash)
      output.write(peerId)
      if (extensions) {
        val handshake = Bencode.encode(mapOf("m" to emptyMap<String, Any>(),
          "reqq" to requestQueue.toLong()))
        output.writeInt(2 + handshake.size)
        output.write(20)
        output.write(0)
        output.write(handshake)
      }
      val bitfield = ByteArray((pieces + 7) / 8)
      for (index in 0 until pieces) {
        bitfield[index / 8] = (bitfield[index / 8].toInt() or (128 ushr (index % 8))).toByte()
      }
      output.writeInt(1 + bitfield.size)
      output.write(5)
      output.write(bitfield)
      output.flush()
      val queue = LinkedBlockingQueue<Out>()
      val canceled = ConcurrentHashMap.newKeySet<Long>()
      spawn("seeder-$port-${socket.port}-writer") { write(socket, output, queue, canceled) }
      while (!closed) {
        val size = input.readInt()
        if (size == 0) continue
        val id = input.readUnsignedByte()
        when (id) {
          2 -> queue += Out.Control(1)
          3 -> queue += Out.Control(0)
          6, 8 -> {
            val index = input.readInt()
            val begin = input.readInt()
            val length = input.readInt()
            input.skipBytes(size - 13)
            if (id == 6) {
              canceled.remove(key(index, begin))
              queue += Out.Block(index, begin, length, System.nanoTime() + delayMs * 1_000_000)
            } else canceled += key(index, begin)
          }
          else -> input.skipBytes(size - 1)
        }
      }
    } catch (_: EOFException) {
    } catch (_: IOException) {
    } finally {
      socket.close()
    }
  }

  private fun key(index: Int, begin: Int) = index.toLong() shl 32 or begin.toLong()

  private fun write(
    socket: Socket,
    output: DataOutputStream,
    queue: LinkedBlockingQueue<Out>,
    canceled: MutableSet<Long>,
  ) {
    var choked = true
    try {
      while (!closed && !socket.isClosed) {
        val next = queue.poll() ?: run {
          output.flush()
          queue.take()
        }
        when (next) {
          is Out.Control -> {
            choked = next.id == 0
            output.writeInt(1)
            output.write(next.id)
          }
          is Out.Block -> {
            val wait = next.due - System.nanoTime()
            if (wait > 0) {
              output.flush()
              Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            // A choked peer's requests are dropped (BEP 3), as are canceled ones.
            if (choked || canceled.remove(key(next.index, next.begin))) continue
            val start = next.index.toLong() * pieceLength + next.begin
            require(next.begin >= 0 && next.length in 1..16_384 && start + next.length <= length)
            output.writeInt(9 + next.length)
            output.write(7)
            output.writeInt(next.index)
            output.writeInt(next.begin)
            output.write(payload, start.toInt(), next.length)
          }
        }
      }
    } catch (_: IOException) {
    } catch (_: InterruptedException) {
    } finally {
      socket.close()
    }
  }

  override fun close() {
    closed = true
    server.close()
    sockets.forEach { it.close() }
    workers.forEach { it.thread.interrupt() }
  }
}
