package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject
import platform.posix.memcpy

@Composable
actual fun rememberFilePicker(): FilePicker {
  val viewController = LocalUIViewController.current
  return remember(viewController) { IosFilePicker(viewController) }
}

/** The document picker, presented over the app's view controller. */
private class IosFilePicker(private val viewController: UIViewController) : FilePicker {
  override val canPickFolder: Boolean = true

  override suspend fun pickFolder(initialFolder: String?): String? {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder))
    initialFolder?.let { picker.directoryURL = NSURL.fileURLWithPath(it, isDirectory = true) }
    val url = pick(picker).firstOrNull() ?: return null
    // A folder outside Ketch's own is writable only with this access, which lasts until the app
    // quits.
    url.startAccessingSecurityScopedResource()
    return url.path
  }

  override suspend fun pickTorrentFiles(): List<DroppedFile> {
    val type = UTType.typeWithIdentifier(TORRENT_TYPE) ?: UTTypeData
    // Copies need no security-scoped access to read later.
    val picker = UIDocumentPickerViewController(
      forOpeningContentTypes = listOf(type),
      asCopy = true,
    )
    picker.allowsMultipleSelection = true
    return pick(picker).map { it.toDroppedFile() }
  }

  private suspend fun pick(picker: UIDocumentPickerViewController): List<NSURL> =
    withContext(Dispatchers.Main) {
      // The picker holds its delegate weakly; this coroutine keeps it alive until it answers.
      val delegate = PickerDelegate()
      picker.delegate = delegate
      val top = viewController.topPresented()
      top.presentViewController(picker, animated = true, completion = null)
      try {
        delegate.result.await()
      } catch (e: CancellationException) {
        picker.dismissViewControllerAnimated(true, completion = null)
        throw e
      }
    }

  private companion object {
    /** Declared by the app in `UTImportedTypeDeclarations`. */
    const val TORRENT_TYPE = "org.bittorrent.torrent"
  }
}

private class PickerDelegate : NSObject(), UIDocumentPickerDelegateProtocol {
  val result = CompletableDeferred<List<NSURL>>()

  override fun documentPicker(
    controller: UIDocumentPickerViewController,
    didPickDocumentsAtURLs: List<*>,
  ) {
    result.complete(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
  }

  override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
    result.complete(emptyList())
  }
}

/** [this] file URL as a [DroppedFile], read on demand with its size bounded. */
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.toDroppedFile(): DroppedFile {
  val name = lastPathComponent ?: "file"
  return DroppedFile(name) { maxBytes ->
    withContext(Dispatchers.Default) {
      // Mapped data is paged in on access, so an oversized file is rejected without loading it.
      val data = NSData.dataWithContentsOfURL(this@toDroppedFile, NSDataReadingMappedIfSafe, null)
        ?: throw IllegalArgumentException("Cannot read $name")
      if (data.length.toLong() > maxBytes) fileTooLarge(name, maxBytes)
      val bytes = ByteArray(data.length.toInt())
      if (bytes.isNotEmpty()) {
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length.convert()) }
      }
      bytes
    }
  }
}
