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
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_cancel
import ketch.app.shared.generated.resources.action_remove
import ketch.app.shared.generated.resources.downloads_remove_and_delete
import ketch.app.shared.generated.resources.downloads_remove_and_delete_size
import ketch.app.shared.generated.resources.downloads_remove_and_trash
import ketch.app.shared.generated.resources.downloads_remove_and_trash_size
import ketch.app.shared.generated.resources.downloads_remove_delete_all
import ketch.app.shared.generated.resources.downloads_remove_delete_file
import ketch.app.shared.generated.resources.downloads_remove_delete_files
import ketch.app.shared.generated.resources.downloads_remove_delete_partial
import ketch.app.shared.generated.resources.downloads_remove_note_free_on
import ketch.app.shared.generated.resources.downloads_remove_note_frees
import ketch.app.shared.generated.resources.downloads_remove_note_frees_after
import ketch.app.shared.generated.resources.downloads_remove_note_partial
import ketch.app.shared.generated.resources.downloads_remove_note_partials
import ketch.app.shared.generated.resources.downloads_remove_size_of
import ketch.app.shared.generated.resources.downloads_remove_subtitle
import ketch.app.shared.generated.resources.downloads_remove_title
import ketch.app.shared.generated.resources.downloads_remove_title_many
import ketch.app.shared.generated.resources.downloads_remove_trash_all
import ketch.app.shared.generated.resources.downloads_remove_trash_file
import ketch.app.shared.generated.resources.downloads_remove_trash_files
import ketch.app.shared.generated.resources.downloads_remove_trash_partial
import org.jetbrains.compose.resources.stringResource

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
 * @property trash whether finished files go to the Trash rather than being deleted: only when
 *   the device offers one and some file can go there. Partial files are deleted either way, since
 *   only the device that wrote them knows where they are.
 */
data class RemovalPlan(val items: List<RemovalItem>, val trash: Boolean) {
  /** Whether any of the downloads has a file to remove. */
  val hasFiles: Boolean get() = items.any { it.hasFile }

  /** Bytes the files take, 0 when unknown. */
  val bytes: Long get() = items.filter { it.hasFile }.sumOf { it.bytes }

  /** The files deleted for good: those that cannot go to the Trash, or all without one. */
  val deleted: List<RemovalItem>
    get() = items.filter { it.hasFile && !(trash && it.trashable) }

  companion object {
    /** The plan for removing [rows]; [canTrash] tells whether their device has a Trash. */
    fun of(rows: List<TaskRow>, canTrash: Boolean): RemovalPlan {
      val items = rows.map(::itemOf)
      return RemovalPlan(items, trash = canTrash && items.any { it.hasFile && it.trashable })
    }

    private fun itemOf(row: TaskRow): RemovalItem {
      val downloaded = row.segments.sumOf { it.downloadedBytes }
      return when (val state = row.state) {
        is DownloadState.Completed -> RemovalItem(
          name = row.name,
          hasFile = state.outputPath.isNotBlank(),
          bytes = state.totalBytes ?: 0,
          fileCount = fileCount(row),
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

    // No selection means every file of the source was downloaded.
    private fun fileCount(row: TaskRow): Int {
      val request = row.request
      val count = request.selectedFileIds.size.takeIf { it > 0 }
        ?: request.resolvedSource?.files?.size
        ?: 1
      return count.coerceAtLeast(1)
    }
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
  val title: UiText,
  val subtitle: UiText,
  val checkbox: UiText?,
  val note: UiText?,
  val confirm: UiText,
  val danger: Boolean,
)

/**
 * The copy of a dialog removing [plan] from [deviceName], with the files box [checked].
 *
 * @param freeBytes free space on the device, for the note; `null` when unknown.
 */
fun removeDialogCopy(
  plan: RemovalPlan,
  deviceName: UiText,
  checked: Boolean,
  freeBytes: Long? = null,
): RemoveDialogCopy {
  val single = plan.items.singleOrNull()
  val title = if (single != null) {
    Res.string.downloads_remove_title.text(single.name)
  } else {
    Res.plurals.downloads_remove_title_many.text(plan.items.size)
  }
  val subtitle = Res.string.downloads_remove_subtitle.text(deviceName)
  val remove = Res.string.action_remove.text()
  if (!plan.hasFiles) return RemoveDialogCopy(title, subtitle, null, null, remove, false)
  val one = plan.items.filter { it.hasFile }.singleOrNull()
  val size = when {
    one != null && one.partial && one.totalBytes != null ->
      Res.string.downloads_remove_size_of.text(sizeText(one.bytes), sizeText(one.totalBytes))
    plan.bytes > 0 -> sizeText(plan.bytes)
    else -> null
  }
  val checkbox = listOfNotNull(filesBox(one, plan.trash), size).joinText()
  val note = if (checked) deletionNote(plan, deviceName, freeBytes) else null
  val confirm = when {
    !checked -> remove
    plan.bytes > 0 -> {
      val freed = sizeText(plan.bytes)
      if (plan.trash) {
        Res.string.downloads_remove_and_trash_size.text(freed)
      } else {
        Res.string.downloads_remove_and_delete_size.text(freed)
      }
    }
    plan.trash -> Res.string.downloads_remove_and_trash.text()
    else -> Res.string.downloads_remove_and_delete.text()
  }
  return RemoveDialogCopy(title, subtitle, checkbox, note, confirm, danger = checked)
}

/**
 * The box that also moves the files to the Trash, with [trash], or deletes them: of the one
 * download with a file, [one], or of several when `null`.
 */
private fun filesBox(one: RemovalItem?, trash: Boolean): UiText = when {
  one == null -> if (trash) {
    Res.string.downloads_remove_trash_files.text()
  } else {
    Res.string.downloads_remove_delete_files.text()
  }
  one.partial -> if (trash) {
    Res.string.downloads_remove_trash_partial.text()
  } else {
    Res.string.downloads_remove_delete_partial.text()
  }
  one.fileCount > 1 -> if (trash) {
    Res.plurals.downloads_remove_trash_all.text(one.fileCount)
  } else {
    Res.plurals.downloads_remove_delete_all.text(one.fileCount)
  }
  trash -> Res.string.downloads_remove_trash_file.text()
  else -> Res.string.downloads_remove_delete_file.text()
}

/**
 * What deleting the files of [plan] for good means: which partial files skip the Trash, and the
 * space that frees, with the space left on the device when every file is deleted. Files in the
 * Trash keep their space until it is emptied, so they free none.
 */
private fun deletionNote(plan: RemovalPlan, deviceName: UiText, freeBytes: Long?): UiText? {
  val deleted = plan.deleted
  val freed = deleted.sumOf { it.bytes }
  val partials = when {
    !plan.trash || deleted.isEmpty() -> null
    deleted.size == 1 -> Res.string.downloads_remove_note_partial.text()
    else -> Res.plurals.downloads_remove_note_partials.text(deleted.size)
  }
  // The note starts with what it frees unless the partial files come first.
  val frees = when {
    freed <= 0 -> null
    partials == null -> Res.string.downloads_remove_note_frees.text(sizeText(freed))
    else -> Res.string.downloads_remove_note_frees_after.text(sizeText(freed))
  }
  val freeOn = freeBytes?.takeIf { freed > 0 && !plan.trash }
    ?.let { Res.string.downloads_remove_note_free_on.text(sizeText(it), deviceName) }
  return listOfNotNull(partials, frees, freeOn).takeIf { it.isNotEmpty() }?.joinText()
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
  deviceName: UiText,
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
    title = { Text(copy.title.resolve()) },
    dismissButton = {
      KetchButton(
        text = stringResource(Res.string.action_cancel),
        variant = KetchButtonVariant.Secondary,
        onClick = onDismiss,
      )
    },
    confirmButton = {
      KetchButton(
        text = copy.confirm.resolve(),
        variant = if (copy.danger) KetchButtonVariant.Danger else KetchButtonVariant.Secondary,
        onClick = {
          onConfirm(checked)
          onDismiss()
        },
      )
    },
  ) {
    Text(text = copy.subtitle.resolve(), style = type.body, color = colors.textSecondary)
    if (copy.checkbox != null) {
      Column {
        KetchCheckbox(
          checked = checked,
          onCheckedChange = { checked = it },
          label = copy.checkbox.resolve(),
        )
        if (copy.note != null) {
          Text(
            text = copy.note.resolve(),
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
