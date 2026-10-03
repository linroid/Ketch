package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.queue_waiting_host
import ketch.app.shared.generated.resources.queue_waiting_slot
import ketch.app.shared.generated.resources.queue_waiting_start

/** Why a queued task has not started, judged like the engine's download queue. */
sealed class QueueReason {
  /** What a row says, such as "Waiting for a free slot (2 of 2 in use)". */
  abstract val text: UiText

  /**
   * Every download slot is taken.
   *
   * @property running number of downloading tasks.
   * @property limit [DownloadConfig.maxConcurrentDownloads].
   */
  data class SlotsFull(val running: Int, val limit: Int) : QueueReason() {
    override val text: UiText
      get() = Res.string.queue_waiting_slot.text(running, limit)
  }

  /**
   * The task's site already runs as many downloads as it may.
   *
   * @property host the task's URL host.
   * @property limit [DownloadConfig.maxConnectionsPerHost].
   */
  data class HostFull(val host: String, val limit: Int) : QueueReason() {
    override val text: UiText
      get() = Res.string.queue_waiting_host.text(host, limit)
  }

  /** No limit holds the task back; it starts when the queue reaches it. */
  data object Next : QueueReason() {
    override val text: UiText
      get() = Res.string.queue_waiting_start.text()
  }

  companion object {
    /**
     * Why the queued task downloading [request] waits on a device with [config], where the
     * tasks downloading [running] occupy the slots.
     */
    fun of(
      request: DownloadRequest,
      config: DownloadConfig,
      running: List<DownloadRequest>,
    ): QueueReason {
      val slots = config.maxConcurrentDownloads
      if (slots > 0 && running.size >= slots) return SlotsFull(running.size, slots)
      // Like the engine, host-less links (magnets, torrent: ids, local files) have no site limit.
      val host = urlHost(request.url) ?: return Next
      val perHost = config.maxConnectionsPerHost
      val onHost = running.count { urlHost(it.url) == host }
      return if (perHost > 0 && onHost >= perHost) HostFull(host, perHost) else Next
    }
  }
}
