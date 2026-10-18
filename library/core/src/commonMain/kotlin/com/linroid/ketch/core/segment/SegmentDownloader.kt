package com.linroid.ketch.core.segment

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.engine.TransferWatchdog
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SegmentDownloader(
  private val httpEngine: HttpEngine,
  private val fileAccessor: FileAccessor,
  private val taskLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  taskId: String? = null,
  private val watchdog: TransferWatchdog = TransferWatchdog.Off,
) {
  private val log = KetchLogger("SegmentDownloader")
  private val owner = taskId?.let { " for taskId=$it" }.orEmpty()

  /**
   * Downloads [lane] with one Range request from its offset to its end. The response is closed
   * early when another connection takes over the lane's tail meanwhile.
   */
  suspend fun download(
    url: String,
    lane: SegmentLane,
    headers: Map<String, String> = emptyMap(),
    handle: ConnectionHandle = ConnectionHandle.None,
  ) {
    if (lane.isComplete) return
    val range = lane.offset..lane.end
    log.d {
      "Starting range ${range.first}..${range.last}$owner " +
        "(${range.last - range.first + 1} bytes remaining)"
    }
    var writeOffset = range.first

    watchdog.watch { monitor ->
      httpEngine.download(url, range, headers) { data ->
        currentCoroutineContext().ensureActive()
        monitor.received(data.size)
        val allowed = lane.admit(data.size)
        if (allowed > 0) {
          monitor.throttled {
            taskLimiter.acquire(allowed)
            globalLimiter.acquire(allowed)
          }
          val chunk = if (allowed == data.size) data else data.copyOf(allowed)
          try {
            fileAccessor.writeAt(writeOffset, chunk)
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is KetchError) throw e
            throw KetchError.Disk(e)
          }
          writeOffset += allowed
          handle.received(allowed)
          // Ends the response when another connection took the rest of the range.
          lane.commit(allowed)
        }
        if (allowed < data.size) {
          throw KetchError.Unsupported(IllegalStateException("Response exceeds segment boundary"))
        }
      }
    }

    if (!lane.isComplete) {
      log.w {
        "Incomplete range ${range.first}..${lane.end}$owner: " +
          "downloaded ${writeOffset - range.first}/${lane.end - range.first + 1} bytes"
      }
      throw KetchError.Network(
        Exception(
          "Connection closed prematurely: received " +
            "${writeOffset - range.first} of ${lane.end - range.first + 1} bytes"
        )
      )
    }
    log.d { "Completed range ${range.first}..${lane.end}$owner" }
  }
}
