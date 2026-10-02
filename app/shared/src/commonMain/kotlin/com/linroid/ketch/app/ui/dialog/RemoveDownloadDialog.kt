package com.linroid.ketch.app.ui.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchCheckbox
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.app.util.formatBytes

/**
 * Asks before removing a download together with its file. Removing a download and keeping the
 * file needs no dialog; the toast offers Undo instead.
 *
 * @param fileName display name of the download.
 * @param deviceName the device the download is on, such as "This Mac".
 * @param totalBytes size of the file, or `null` when unknown.
 * @param onConfirm removes the download, and its file when `deleteFiles` is set.
 * @param deleteFiles whether the box that deletes the file starts checked.
 */
@Composable
fun RemoveDownloadDialog(
  fileName: String,
  deviceName: String,
  totalBytes: Long?,
  onDismiss: () -> Unit,
  onConfirm: (deleteFiles: Boolean) -> Unit,
  deleteFiles: Boolean = true,
) {
  var delete by remember { mutableStateOf(deleteFiles) }
  val size = totalBytes?.takeIf { it > 0 }?.let(::formatBytes)
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text("Remove “$fileName”?") },
    dismissButton = {
      KetchButton(text = "Cancel", variant = KetchButtonVariant.Secondary, onClick = onDismiss)
    },
    confirmButton = {
      KetchButton(
        text = if (delete) listOfNotNull("Remove and delete", size).joinToString(" ") else "Remove",
        variant = if (delete) KetchButtonVariant.Danger else KetchButtonVariant.Secondary,
        onClick = {
          onConfirm(delete)
          onDismiss()
        },
      )
    },
  ) {
    Text(
      text = "From the list on $deviceName.",
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textSecondary,
    )
    KetchCheckbox(
      checked = delete,
      onCheckedChange = { delete = it },
      label = listOfNotNull("Also delete the file permanently", size).joinToString(" · "),
    )
  }
}
