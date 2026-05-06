package com.linroid.ketch.app.ui.common

import androidx.compose.runtime.Composable
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.util.priorityLabel

@Composable
fun PriorityBadge(priority: DownloadPriority) {
  val tone = when (priority) {
    DownloadPriority.LOW -> KetchBadgeTone.Neutral
    DownloadPriority.NORMAL -> KetchBadgeTone.Accent
    DownloadPriority.HIGH -> KetchBadgeTone.Warning
    DownloadPriority.URGENT -> KetchBadgeTone.Danger
  }
  KetchBadge(text = priorityLabel(priority), tone = tone)
}
