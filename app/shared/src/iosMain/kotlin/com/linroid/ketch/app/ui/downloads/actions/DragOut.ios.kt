package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import com.linroid.ketch.app.state.TaskKey

// Rows are not dragged out on iOS; the share sheet hands files to other apps.
internal actual fun dragTransferData(payload: DragPayload): DragAndDropTransferData? = null

internal actual fun draggedTaskKeys(event: DragAndDropEvent): List<TaskKey> = emptyList()
