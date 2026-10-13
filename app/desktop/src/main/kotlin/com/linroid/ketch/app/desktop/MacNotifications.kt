package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Posts notifications through macOS's `NSUserNotificationCenter`, as AWT's tray icon does, but
 * with a delegate that reports clicks: [post] names each notification, and [onClick] receives
 * the name of the one the user clicked. A notification replaces the one posted under its name.
 *
 * Only an app bundle has a notification center, so [available] is `false` when Ketch runs from
 * a build directory, as well as on other systems.
 */
internal object MacNotifications {
  private val log = KetchLogger("MacNotifications")
  private val pending = ConcurrentLinkedQueue<Posting>()

  /** Receives the name of each notification clicked, on the AppKit main thread. */
  @Volatile
  var onClick: ((String) -> Unit)? = null

  /** Whether [post] can show notifications. */
  val available: Boolean by lazy {
    if (DesktopOs.current != DesktopOs.MAC) return@lazy false
    try {
      val bundle = send(objcClass("NSBundle"), "mainBundle")
      bundle != null && send(bundle, "bundleIdentifier") != null
    } catch (e: Throwable) {
      log.w { "Couldn't look for the notification center: ${e.describeCauses()}" }
      false
    }
  }

  // Runs on the main queue; held here so JNA keeps it alive. Each run posts what is pending.
  private val drain = object : MainQueueFunction {
    override fun invoke(context: Pointer?) {
      while (true) {
        val posting = pending.poll() ?: return
        try {
          deliver(posting)
        } catch (e: Throwable) {
          log.w { "Couldn't post a notification: ${e.describeCauses()}" }
        }
      }
    }
  }

  // The delegate's methods, held here so JNA keeps them alive.
  private val shouldPresent = object : ShouldPresent {
    // Shown while Ketch is in front too, as AWT's delegate does.
    override fun invoke(self: Pointer?, cmd: Pointer?, center: Pointer?, notification: Pointer?) =
      1.toByte()
  }

  private val didActivate = object : DidActivate {
    override fun invoke(self: Pointer?, cmd: Pointer?, center: Pointer?, notification: Pointer?) {
      try {
        if (center == null || notification == null) return
        val name = send(notification, "identifier")
          ?.let { send(it, "UTF8String") }
          ?.getString(0, "UTF-8")
        send(center, "removeDeliveredNotification:", notification)
        if (name != null) onClick?.invoke(name)
      } catch (e: Throwable) {
        log.w { "Couldn't handle a notification click: ${e.describeCauses()}" }
      }
    }
  }

  private val natives by lazy {
    Natives(NativeLibrary.getInstance("objc"), NativeLibrary.getInstance("System"))
  }

  // Created on the main queue by the first post.
  private var delegate: Pointer? = null

  /** Posts [title] and [body] under [name]; check [available] first. */
  fun post(name: String, title: String, body: String) {
    pending += Posting(name, title, body)
    try {
      val natives = natives
      natives.dispatchAsync.invokeVoid(arrayOf(natives.mainQueue, null, drain))
    } catch (e: Throwable) {
      log.w { "Couldn't post a notification: ${e.describeCauses()}" }
    }
  }

  private fun deliver(posting: Posting) {
    val center = send(objcClass("NSUserNotificationCenter"), "defaultUserNotificationCenter")
      ?: return
    // AWT's tray icon makes itself the delegate when it is created, so this one is set each time.
    send(center, "setDelegate:", delegate())
    val notification = checkNotNull(send(objcClass("NSUserNotification"), "alloc"))
      .let { checkNotNull(send(it, "init")) }
    send(notification, "setIdentifier:", nsString(posting.name))
    send(notification, "setTitle:", nsString(posting.title))
    send(notification, "setInformativeText:", nsString(posting.body))
    send(center, "deliverNotification:", notification)
    send(notification, "release")
  }

  private fun delegate(): Pointer = delegate ?: run {
    val natives = natives
    val name = "KetchNotificationDelegate"
    val existing = natives.getClass.invokePointer(arrayOf(name))
    val type = existing ?: natives.allocateClassPair
      .invokePointer(arrayOf(objcClass("NSObject"), name, 0L))
      .also { type ->
        addMethod(type, SHOULD_PRESENT, shouldPresent, "c@:@@")
        addMethod(type, DID_ACTIVATE, didActivate, "v@:@@")
        natives.registerClassPair.invokeVoid(arrayOf(type))
      }
    checkNotNull(send(checkNotNull(send(type, "alloc")), "init")).also { delegate = it }
  }

  private fun addMethod(type: Pointer, selector: String, imp: Callback, types: String) {
    val sel = natives.registerName.invokePointer(arrayOf(selector))
    natives.addMethod.invokeInt(arrayOf(type, sel, imp, types))
  }

  private fun nsString(text: String): Pointer? {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val chars = Memory(bytes.size + 1L)
    chars.write(0, bytes, 0, bytes.size)
    chars.setByte(bytes.size.toLong(), 0)
    return send(objcClass("NSString"), "stringWithUTF8String:", chars)
  }

  private fun objcClass(name: String): Pointer =
    natives.getClass.invokePointer(arrayOf(name))

  private fun send(receiver: Pointer, selector: String, vararg args: Any?): Pointer? {
    val sel = natives.registerName.invokePointer(arrayOf(selector))
    return natives.msgSend.invokePointer(arrayOf(receiver, sel, *args))
  }

  private const val SHOULD_PRESENT = "userNotificationCenter:shouldPresentNotification:"
  private const val DID_ACTIVATE = "userNotificationCenter:didActivateNotification:"

  private class Posting(val name: String, val title: String, val body: String)

  private class Natives(objc: NativeLibrary, system: NativeLibrary) {
    val getClass: Function = objc.getFunction("objc_getClass")
    val registerName: Function = objc.getFunction("sel_registerName")
    val msgSend: Function = objc.getFunction("objc_msgSend")
    val allocateClassPair: Function = objc.getFunction("objc_allocateClassPair")
    val registerClassPair: Function = objc.getFunction("objc_registerClassPair")
    val addMethod: Function = objc.getFunction("class_addMethod")
    val dispatchAsync: Function = system.getFunction("dispatch_async_f")

    // dispatch_get_main_queue() is a macro for this symbol's address.
    val mainQueue: Pointer = system.getGlobalVariableAddress("_dispatch_main_q")
  }

  /** A `dispatch_function_t`. */
  private interface MainQueueFunction : Callback {
    fun invoke(context: Pointer?)
  }

  /** `-userNotificationCenter:shouldPresentNotification:`, which returns a `BOOL`. */
  private interface ShouldPresent : Callback {
    fun invoke(self: Pointer?, cmd: Pointer?, center: Pointer?, notification: Pointer?): Byte
  }

  /** `-userNotificationCenter:didActivateNotification:`. */
  private interface DidActivate : Callback {
    fun invoke(self: Pointer?, cmd: Pointer?, center: Pointer?, notification: Pointer?)
  }
}
