package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Shows Ketch in the macOS Dock and the app switcher only while [shown], as while one of its
 * windows is open; otherwise Ketch lives in the menu bar alone. Does nothing on other systems.
 */
@Composable
internal fun MacDockPresence(shown: Boolean) {
  if (DesktopOs.current != DesktopOs.MAC) return
  LaunchedEffect(shown) { MacDock.show(shown) }
}

/**
 * Switches the app's activation policy between regular (a Dock icon and a menu bar) and accessory
 * (neither), through the Objective-C runtime on the AppKit main thread.
 */
private object MacDock {
  // NSApplicationActivationPolicyRegular and NSApplicationActivationPolicyAccessory.
  private const val POLICY_REGULAR = 0L
  private const val POLICY_ACCESSORY = 1L

  private val log = KetchLogger("MacDock")

  @Volatile private var shown = true

  // Runs on the main queue; held here so JNA keeps it alive. It reads the latest wish, so quick
  // changes settle on the last one.
  private val apply = object : MainQueueFunction {
    override fun invoke(context: Pointer?) {
      try {
        val app = checkNotNull(send(objcClass("NSApplication"), "sharedApplication"))
        val policy = if (shown) POLICY_REGULAR else POLICY_ACCESSORY
        send(app, "setActivationPolicy:", policy)
        // A regular app's menu bar only shows once the app is activated again.
        if (shown) send(app, "activateIgnoringOtherApps:", 1)
      } catch (e: Throwable) {
        log.w { "Couldn't change the Dock icon: ${e.describeCauses()}" }
      }
    }
  }

  private val natives by lazy {
    Natives(NativeLibrary.getInstance("objc"), NativeLibrary.getInstance("System"))
  }

  fun show(shown: Boolean) {
    // Apps start with a Dock icon.
    if (this.shown == shown) return
    this.shown = shown
    try {
      val natives = natives
      natives.dispatchAsync.invokeVoid(arrayOf(natives.mainQueue, null, apply))
    } catch (e: Throwable) {
      log.w { "Couldn't change the Dock icon: ${e.describeCauses()}" }
    }
  }

  private fun objcClass(name: String): Pointer =
    natives.getClass.invokePointer(arrayOf(name))

  private fun send(receiver: Pointer, selector: String, vararg args: Any): Pointer? {
    val sel = natives.registerName.invokePointer(arrayOf(selector))
    return natives.msgSend.invokePointer(arrayOf(receiver, sel, *args))
  }

  private class Natives(objc: NativeLibrary, system: NativeLibrary) {
    val getClass: Function = objc.getFunction("objc_getClass")
    val registerName: Function = objc.getFunction("sel_registerName")
    val msgSend: Function = objc.getFunction("objc_msgSend")
    val dispatchAsync: Function = system.getFunction("dispatch_async_f")

    // dispatch_get_main_queue() is a macro for this symbol's address.
    val mainQueue: Pointer = system.getGlobalVariableAddress("_dispatch_main_q")
  }

  /** A `dispatch_function_t`. */
  private interface MainQueueFunction : Callback {
    fun invoke(context: Pointer?)
  }
}
