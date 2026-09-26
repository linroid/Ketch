package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.UniformTypeIdentifiers.UTType
import platform.posix.memcpy

@Composable
internal actual fun rememberFileDropReader(): FileDropReader = UIKitFileDropReader

/** UIKit reports exits to drop targets itself. */
@Composable
internal actual fun DragExitEffect(onExit: () -> Unit) = Unit

/** Reads files dragged in from other apps, e.g. Files on iPad. */
@OptIn(ExperimentalComposeUiApi::class)
private object UIKitFileDropReader : FileDropReader {
  override fun hasFiles(event: DragAndDropEvent): Boolean =
    event.items.any { it.itemProvider.hasItemConformingToTypeIdentifier(DATA_TYPE) }

  override fun files(event: DragAndDropEvent): List<DroppedFile> =
    event.items.map { it.itemProvider }
      .filter { it.hasItemConformingToTypeIdentifier(DATA_TYPE) }
      .map { provider ->
        val name = provider.fileName()
        DroppedFile(name) { maxBytes -> provider.loadData(name, maxBytes) }
      }
}

private const val DATA_TYPE = "public.data"

/** The suggested name may omit the extension, which the registered type still carries. */
private fun NSItemProvider.fileName(): String {
  val name = suggestedName ?: "file"
  val extension = registeredTypeIdentifiers.firstNotNullOfOrNull { id ->
    (id as? String)?.let { UTType.typeWithIdentifier(it)?.preferredFilenameExtension }
  } ?: return name
  return if (name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"
}

private suspend fun NSItemProvider.loadData(name: String, maxBytes: Long): ByteArray =
  suspendCancellableCoroutine { continuation ->
    val progress = loadDataRepresentationForTypeIdentifier(DATA_TYPE) { data, error ->
      continuation.resumeWith(runCatching {
        checkNotNull(data) { error?.localizedDescription ?: "Cannot read $name" }
        if (data.length.toLong() > maxBytes) fileTooLarge(name, maxBytes)
        data.toByteArray()
      })
    }
    continuation.invokeOnCancellation { progress.cancel() }
  }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
  val bytes = ByteArray(length.toInt())
  if (bytes.isNotEmpty()) {
    bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
  }
  return bytes
}
