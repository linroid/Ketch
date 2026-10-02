package com.linroid.ketch.app.ui.common

import androidx.compose.runtime.Composable
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.app.components.PriorityGlyph

/** Marks a task's [priority]; kept for existing callers of [PriorityGlyph]. */
@Composable
fun PriorityBadge(priority: DownloadPriority) {
  PriorityGlyph(priority)
}
