package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.UniformTypeIdentifiers.UTType
import platform.posix.memcpy
import kotlin.coroutines.resume

@Composable
internal actual fun rememberFileDropReader(): FileDropReader = UIKitFileDropReader

/** UIKit reports exits to drop targets itself. */
@Composable
internal actual fun DragExitEffect(onExit: () -> Unit) = Unit

/**
 * Reads what other apps drop, e.g. from Files or Safari on iPad: files, and links or selected
 * text as text.
 */
@OptIn(ExperimentalComposeUiApi::class)
private object UIKitFileDropReader : FileDropReader {
  private val log = KetchLogger("FileDrop")

  override fun accepts(event: DragAndDropEvent): Boolean =
    event.providers().any { it.hasItemConformingToTypeIdentifier(DATA_TYPE) }

  override fun files(event: DragAndDropEvent): List<DroppedFile> =
    event.providers()
      .filter { it.hasItemConformingToTypeIdentifier(DATA_TYPE) && !it.isText() }
      .map { provider ->
        val name = provider.fileName()
        DroppedFile(name) { maxBytes -> provider.loadData(name, maxBytes) }
      }

  override fun text(event: DragAndDropEvent): (suspend () -> String)? {
    val providers = event.providers().filter { it.isText() }
    if (providers.isEmpty()) return null
    return { providers.mapNotNull { it.loadText() }.joinToString("\n") }
  }

  private fun DragAndDropEvent.providers(): List<NSItemProvider> = items.map { it.itemProvider }

  /** A link, but not a file's, or plain text such as a selection. */
  private fun NSItemProvider.isText(): Boolean {
    val link = hasItemConformingToTypeIdentifier(URL_TYPE) &&
      !hasItemConformingToTypeIdentifier(FILE_URL_TYPE)
    return link || hasItemConformingToTypeIdentifier(PLAIN_TEXT_TYPE)
  }

  /** The text or link, or `null` when it cannot be loaded. */
  private suspend fun NSItemProvider.loadText(): String? {
    val type = TEXT_TYPES.firstOrNull { hasItemConformingToTypeIdentifier(it) } ?: return null
    return suspendCancellableCoroutine { continuation ->
      val progress = loadDataRepresentationForTypeIdentifier(type) { data, error ->
        if (data == null) {
          log.w { "Couldn't read dropped text: ${error?.localizedDescription}" }
        }
        continuation.resume(data?.toByteArray()?.decodeToString())
      }
      continuation.invokeOnCancellation { progress.cancel() }
    }
  }
}

private const val DATA_TYPE = "public.data"
private const val URL_TYPE = "public.url"
private const val FILE_URL_TYPE = "public.file-url"
private const val PLAIN_TEXT_TYPE = "public.plain-text"
private const val UTF8_TEXT_TYPE = "public.utf8-plain-text"

/** Types to read dropped text as, best first; a link's URL data holds its address. */
private val TEXT_TYPES = listOf(UTF8_TEXT_TYPE, URL_TYPE)

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
