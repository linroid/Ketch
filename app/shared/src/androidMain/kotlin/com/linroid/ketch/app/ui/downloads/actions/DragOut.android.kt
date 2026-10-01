package com.linroid.ketch.app.ui.downloads.actions

import android.content.ClipData
import android.view.View
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import com.linroid.ketch.app.state.TaskKey

// Finished files stay behind: other apps would need a content URI and a grant to read them.
internal actual fun dragTransferData(payload: DragPayload): DragAndDropTransferData? =
  DragAndDropTransferData(
    clipData = ClipData.newPlainText("Links", payload.links.joinToString("\n")),
    localState = payload.keysText,
    flags = View.DRAG_FLAG_GLOBAL,
  )

internal actual fun draggedTaskKeys(event: DragAndDropEvent): List<TaskKey> =
  (event.toAndroidDragEvent().localState as? String)?.let(DragPayload::keysOf).orEmpty()
