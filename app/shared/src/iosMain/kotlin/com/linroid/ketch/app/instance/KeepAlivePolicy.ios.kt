package com.linroid.ketch.app.instance

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

internal actual fun appForegroundChanges(): Flow<Boolean> = callbackFlow {
  val center = NSNotificationCenter.defaultCenter
  val queue = NSOperationQueue.mainQueue
  val observers = listOf(
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, queue) {
      trySend(false)
    },
    center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, queue) {
      trySend(true)
    },
  )
  awaitClose { observers.forEach { center.removeObserver(it) } }
}
