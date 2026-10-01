package com.linroid.ketch.app.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchCheckbox
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.app.util.formatBytes

/**
 * One download a [RemoveTasksDialog] removes, as far as its copy needs it.
 *
 * @property name display name of the download.
 * @property hasFile whether removing it with its files frees anything: a finished file or a
 *   partial one with bytes in it.
 * @property bytes bytes its file takes: the finished size, or what a partial file holds; 0 when
 *   unknown.
 * @property totalBytes full size of a partial file, for "812 MB of 2.1 GB"; `null` when unknown.
 * @property partial whether the file is unfinished.
 * @property fileCount how many files it saved, more than one for a multi-file torrent.
 * @property trashable whether its file can be moved to the Trash: it finished and its path is
 *   known. Partial files are deleted by the device that wrote them.
 */
data class RemovalItem(
  val name: String,
  val hasFile: Boolean,
  val bytes: Long = 0,
  val totalBytes: Long? = null,
  val partial: Boolean = false,
  val fileCount: Int = 1,
  val trashable: Boolean = false,
)

/**
 * What a [RemoveTasksDialog] removes and where the files go.
 *
 * @property items the downloads, in display order.
 * @property trash whether their files go to the Trash rather than being deleted: only when the
 *   device offers one and every file can go there.
 */
data class RemovalPlan(val items: List<RemovalItem>, val trash: Boolean) {
  /** Whether any of the downloads has a file to remove. */
  val hasFiles: Boolean get() = items.any { it.hasFile }

  /** Bytes the files take, 0 when unknown. */
  val bytes: Long get() = items.filter { it.hasFile }.sumOf { it.bytes }

  companion object {
    /** The plan for removing [rows]; [canTrash] tells whether their device has a Trash. */
    fun of(rows: List<TaskRow>, canTrash: Boolean): RemovalPlan {
      val items = rows.map(::itemOf)
      val withFiles = items.filter { it.hasFile }
      return RemovalPlan(items, trash = canTrash && withFiles.all { it.trashable })
    }

    private fun itemOf(row: TaskRow): RemovalItem {
      val downloaded = row.segments.sumOf { it.downloadedBytes }
      return when (val state = row.state) {
        is DownloadState.Completed -> RemovalItem(
          name = row.name,
          hasFile = state.outputPath.isNotBlank(),
          bytes = state.totalBytes ?: 0,
          fileCount = row.request.selectedFileIds.size.coerceAtLeast(1),
          trashable = state.outputPath.isNotBlank(),
        )
        is DownloadState.Downloading -> partial(row, state.progress)
        is DownloadState.Paused -> partial(row, state.progress)
        is DownloadState.Canceled -> RemovalItem(row.name, hasFile = false)
        else -> partial(row, downloaded, row.sizeBytes ?: 0)
      }
    }

    private fun partial(row: TaskRow, progress: DownloadProgress) =
      partial(row, progress.downloadedBytes, progress.totalBytes)

    private fun partial(row: TaskRow, downloaded: Long, total: Long) = RemovalItem(
      name = row.name,
      hasFile = downloaded > 0,
      bytes = downloaded,
      totalBytes = total.takeIf { it > 0 },
      partial = true,
    )
  }
}

/**
 * The text of a [RemoveTasksDialog].
 *
 * @property title "Remove “ubuntu.iso”?" or "Remove 3 downloads?".
 * @property subtitle which list the downloads leave.
 * @property checkbox the box that also removes the files; `null` when none has a file.
 * @property note what removing the files frees, shown while the box is checked; `null` when
 *   nothing to say.
 * @property confirm the confirm button.
 * @property danger whether the confirm button removes files, which makes it a Danger button.
 */
data class RemoveDialogCopy(
  val title: String,
  val subtitle: String,
  val checkbox: String?,
  val note: String?,
  val confirm: String,
  val danger: Boolean,
)

/**
 * The copy of a dialog removing [plan] from [deviceName], with the files box [checked].
 *
 * @param freeBytes free space on the device, for the note; `null` when unknown.
 */
fun removeDialogCopy(
  plan: RemovalPlan,
  deviceName: String,
  checked: Boolean,
  freeBytes: Long? = null,
): RemoveDialogCopy {
  val single = plan.items.singleOrNull()
  val title = if (single != null) {
    "Remove “${single.name}”?"
  } else {
    "Remove ${plan.items.size} downloads?"
  }
  val subtitle = "From the list on $deviceName."
  if (!plan.hasFiles) return RemoveDialogCopy(title, subtitle, null, null, "Remove", false)
  val withFiles = plan.items.filter { it.hasFile }
  val one = withFiles.singleOrNull()
  val noun = when {
    one == null -> "the files"
    one.partial -> "the partial file"
    one.fileCount > 1 -> "all ${one.fileCount} files"
    else -> "the file"
  }
  val size = when {
    one != null && one.partial && one.totalBytes != null ->
      "${formatBytes(one.bytes)} of ${formatBytes(one.totalBytes)}"
    plan.bytes > 0 -> formatBytes(plan.bytes)
    else -> null
  }
  val box = if (plan.trash) "Also move $noun to the Trash" else "Also delete $noun permanently"
  val checkbox = listOfNotNull(box, size).joinToString(" · ")
  // Files in the Trash still take their space until it is emptied.
  val note = if (checked && !plan.trash && plan.bytes > 0) {
    listOfNotNull(
      "Frees ${formatBytes(plan.bytes)}",
      freeBytes?.let { "${formatBytes(it)} free on $deviceName" },
    ).joinToString(" · ")
  } else {
    null
  }
  val verb = if (plan.trash) "Remove and trash" else "Remove and delete"
  val confirm = when {
    !checked -> "Remove"
    plan.bytes > 0 -> "$verb ${formatBytes(plan.bytes)}"
    else -> verb
  }
  return RemoveDialogCopy(title, subtitle, checkbox, note, confirm, danger = checked)
}

/**
 * Whether the files box of a dialog removing [plan] starts checked: only when there are files
 * and the dialog was opened to remove them too ([withFiles]), as ⇧⌫ does.
 */
fun initiallyChecked(plan: RemovalPlan, withFiles: Boolean): Boolean = withFiles && plan.hasFiles

/**
 * Asks before removing downloads, with a box that also moves their files to the Trash or, where
 * there is none, deletes them. The box starts unchecked unless [withFiles], as when ⇧⌫ opened
 * the dialog. Removing downloads and keeping their files needs no dialog; ⌫ removes them with
 * Undo.
 *
 * @param plan what is removed and where its files go.
 * @param deviceName the device the downloads are on, such as "This Mac".
 * @param freeBytes free space on that device, for the note; `null` when unknown.
 * @param onConfirm removes the downloads, and their files when `withFiles` is set.
 */
@Composable
fun RemoveTasksDialog(
  plan: RemovalPlan,
  deviceName: String,
  onDismiss: () -> Unit,
  onConfirm: (withFiles: Boolean) -> Unit,
  withFiles: Boolean = false,
  freeBytes: Long? = null,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  var checked by remember { mutableStateOf(initiallyChecked(plan, withFiles)) }
  val copy = removeDialogCopy(plan, deviceName, checked, freeBytes)
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text(copy.title) },
    dismissButton = {
      KetchButton(text = "Cancel", variant = KetchButtonVariant.Secondary, onClick = onDismiss)
    },
    confirmButton = {
      KetchButton(
        text = copy.confirm,
        variant = if (copy.danger) KetchButtonVariant.Danger else KetchButtonVariant.Secondary,
        onClick = {
          onConfirm(checked)
          onDismiss()
        },
      )
    },
  ) {
    Text(text = copy.subtitle, style = type.body, color = colors.textSecondary)
    if (copy.checkbox != null) {
      Column {
        KetchCheckbox(checked = checked, onCheckedChange = { checked = it }, label = copy.checkbox)
        if (copy.note != null) {
          Text(
            text = copy.note,
            style = type.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(
              start = KetchTheme.density.controlGlyph + KetchTheme.spacing.iconLabelGap,
            ),
          )
        }
      }
    }
  }
}

/**
 * Asks before removing one download together with its file.
 *
 * @param totalBytes size of the file, or `null` when unknown.
 * @param deleteFiles whether the box that deletes the file starts checked.
 */
@Deprecated("Use RemoveTasksDialog, which also covers several downloads and the Trash.")
@Composable
fun RemoveDownloadDialog(
  fileName: String,
  deviceName: String,
  totalBytes: Long?,
  onDismiss: () -> Unit,
  onConfirm: (deleteFiles: Boolean) -> Unit,
  deleteFiles: Boolean = true,
) {
  val item = RemovalItem(name = fileName, hasFile = true, bytes = totalBytes ?: 0)
  RemoveTasksDialog(
    plan = RemovalPlan(listOf(item), trash = false),
    deviceName = deviceName,
    onDismiss = onDismiss,
    onConfirm = onConfirm,
    withFiles = deleteFiles,
  )
}
