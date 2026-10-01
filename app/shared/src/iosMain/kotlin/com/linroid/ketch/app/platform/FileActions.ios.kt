package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.QuickLook.QLPreviewController
import platform.QuickLook.QLPreviewControllerDataSourceProtocol
import platform.QuickLook.QLPreviewItemProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import kotlin.coroutines.resume

@Composable
actual fun rememberFileActions(): FileActions? {
  val viewController = LocalUIViewController.current
  return remember(viewController) { IosFileActions(viewController) }
}

/**
 * Previews downloads with Quick Look, shares them with the share sheet and shows their folder
 * in the Files app, where iOS lets Ketch open it.
 */
private class IosFileActions(private val viewController: UIViewController) : FileActions {
  override val revealLabel: String? =
    if (UIApplication.sharedApplication.canOpenURL(NSURL(string = FILES_SCHEME))) {
      "Show in Files"
    } else {
      null
    }

  override val canShare: Boolean = true

  override val canTrash: Boolean = false

  override suspend fun open(path: String) = withContext(Dispatchers.Main) {
    val url = existingUrl(path)
    if (isDirectory(path)) throw FileActionException("Open the files in ${name(path)} in Files")
    viewController.topPresented().presentViewController(
      FilePreviewController(url, name(path)),
      animated = true,
      completion = null,
    )
  }

  override suspend fun reveal(path: String) = withContext(Dispatchers.Main) {
    val folder = generateSequence(parentOf(path), ::parentOf).firstOrNull { isDirectory(it) }
      ?: throw FileActionException("The folder of ${name(path)} was moved or deleted")
    val fileUrl = NSURL.fileURLWithPath(folder, isDirectory = true).absoluteString
    val url = fileUrl?.let { NSURL(string = FILES_SCHEME + it.removePrefix("file://")) }
      ?: throw FileActionException("Couldn't show ${name(path)} in Files")
    val opened = suspendCancellableCoroutine { continuation ->
      UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>()) { success ->
        continuation.resume(success)
      }
    }
    if (!opened) throw FileActionException("Couldn't show ${name(path)} in Files")
  }

  @OptIn(ExperimentalForeignApi::class)
  override suspend fun share(path: String) = withContext(Dispatchers.Main) {
    val sheet = UIActivityViewController(
      activityItems = listOf(existingUrl(path)),
      applicationActivities = null,
    )
    // On iPad the sheet is a popover, which needs an anchor; it opens mid-screen.
    sheet.popoverPresentationController?.let { popover ->
      val view = viewController.view
      popover.sourceView = view
      popover.sourceRect = view.bounds.useContents {
        CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0)
      }
      popover.permittedArrowDirections = 0uL
    }
    viewController.topPresented().presentViewController(sheet, animated = true, completion = null)
  }

  override suspend fun exists(path: String): Boolean =
    NSFileManager.defaultManager.fileExistsAtPath(path)

  override suspend fun moveToTrash(path: String) {
    throw FileActionException("iOS has no Trash for downloads")
  }

  private fun existingUrl(path: String): NSURL {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
      throw FileActionException("${name(path)} was moved or deleted")
    }
    return NSURL.fileURLWithPath(path)
  }

  private companion object {
    /** Opens a folder in the Files app; it must be listed in `LSApplicationQueriesSchemes`. */
    const val FILES_SCHEME = "shareddocuments://"
  }
}

/** A Quick Look preview of one file, its own data source so that it outlives any caller. */
private class FilePreviewController(url: NSURL, title: String) :
  QLPreviewController(nibName = null, bundle = null), QLPreviewControllerDataSourceProtocol {
  private val item = PreviewItem(url, title)

  init {
    dataSource = this
  }

  override fun numberOfPreviewItemsInPreviewController(controller: QLPreviewController): Long = 1

  override fun previewController(
    controller: QLPreviewController,
    previewItemAtIndex: Long,
  ): QLPreviewItemProtocol = item
}

private class PreviewItem(private val url: NSURL, private val title: String) :
  NSObject(), QLPreviewItemProtocol {
  override fun previewItemURL(): NSURL = url

  override fun previewItemTitle(): String = title
}

private fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

private fun parentOf(path: String): String? =
  path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { null }

@OptIn(ExperimentalForeignApi::class)
private fun isDirectory(path: String): Boolean = memScoped {
  val directory = alloc<BooleanVar>()
  NSFileManager.defaultManager.fileExistsAtPath(path, isDirectory = directory.ptr) &&
    directory.value
}

/** The controller on top of any sheets presented over this one, which can present another. */
internal fun UIViewController.topPresented(): UIViewController =
  presentedViewController?.topPresented() ?: this
