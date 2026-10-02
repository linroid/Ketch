package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIUserInterfaceStyle

@Composable
actual fun SystemAppearance(dark: Boolean?) {
  val controller = LocalUIViewController.current
  val style = when (dark) {
    null -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
    true -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
    false -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
  }
  DisposableEffect(controller, style) {
    // The window, once the view is in one, carries the style to the sheets the app presents.
    controller.overrideUserInterfaceStyle = style
    controller.view.window?.overrideUserInterfaceStyle = style
    controller.setNeedsStatusBarAppearanceUpdate()
    onDispose {}
  }
}
