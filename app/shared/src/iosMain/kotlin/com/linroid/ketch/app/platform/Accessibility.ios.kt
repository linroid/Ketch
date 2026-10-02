package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

@Composable
actual fun rememberReduceMotion(): Boolean {
  var reduce by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
  DisposableEffect(Unit) {
    val center = NSNotificationCenter.defaultCenter
    val observer = center.addObserverForName(
      name = UIAccessibilityReduceMotionStatusDidChangeNotification,
      `object` = null,
      queue = NSOperationQueue.mainQueue,
    ) { _ -> reduce = UIAccessibilityIsReduceMotionEnabled() }
    onDispose { center.removeObserver(observer) }
  }
  return reduce
}
