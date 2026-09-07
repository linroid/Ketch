package com.linroid.ketch.app.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.linroid.ketch.app.theme.KetchTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.util.formatBytes

@Composable
fun RemoveDownloadDialog(
  fileName: String,
  totalBytes: Long?,
  onDismiss: () -> Unit,
  onConfirm: (deleteFiles: Boolean) -> Unit,
) {
  var deleteFiles by remember { mutableStateOf(false) }

  AlertDialog(
    containerColor = KetchTheme.colors.surface,
    tonalElevation = 0.dp,
    shape = RoundedCornerShape(20.dp),
    onDismissRequest = onDismiss,
    title = { Text("Remove download?") },
    text = {
      Column {
        Text(
          text = fileName,
          fontWeight = FontWeight.Medium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (totalBytes != null && totalBytes > 0) {
          Text(
            text = formatBytes(totalBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(12.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { deleteFiles = !deleteFiles }
            .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Checkbox(
            checked = deleteFiles,
            onCheckedChange = { deleteFiles = it },
          )
          Text("Also delete downloaded file")
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onConfirm(deleteFiles)
          onDismiss()
        },
      ) {
        Text("Remove")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}
