package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import com.linroid.ketch.app.state.TaskKey

// Compose for the web cannot start a drag that carries data yet.
internal actual fun dragTransferData(payload: DragPayload): DragAndDropTransferData? = null

internal actual fun draggedTaskKeys(event: DragAndDropEvent): List<TaskKey> = emptyList()
