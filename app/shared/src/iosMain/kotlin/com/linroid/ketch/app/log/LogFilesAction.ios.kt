package com.linroid.ketch.app.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import okio.Path.Companion.toPath
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberLogFilesAction(logger: FileLogger): LogFilesAction? {
  val viewController = LocalUIViewController.current
  return remember(logger, viewController) {
    shareLogsAction {
      val path = NSTemporaryDirectory().toPath() / SHARED_LOG_FILE_NAME
      logger.exportTo(path)
      val sheet = UIActivityViewController(
        activityItems = listOf(NSURL.fileURLWithPath(path.toString())),
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
  }
}

/** The controller on top of any sheets presented over this one, which can present another. */
private fun UIViewController.topPresented(): UIViewController =
  presentedViewController?.topPresented() ?: this
