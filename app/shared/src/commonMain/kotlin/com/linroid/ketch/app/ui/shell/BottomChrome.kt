package com.linroid.ketch.app.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The controls a page keeps along the bottom of the content, such as Discover's composer, which
 * the shell's toasts float above. A page with such controls reports their height with
 * [reportsBottomChrome]; it drops back to zero when they leave.
 */
@Stable
internal class BottomChrome {
  /** Height of the page's bottom controls, keyboard room included; zero without any. */
  var height: Dp by mutableStateOf(0.dp)
}

/** The shell's [BottomChrome], for the page shown; `null` outside the shell. */
internal val LocalBottomChrome = staticCompositionLocalOf<BottomChrome?> { null }

/**
 * Height of the phone's bottom bar, the navigation bar's inset under it included, which the
 * soft keyboard covers before it reaches the content; zero without a bottom bar.
 */
internal val LocalPhoneBottomBarHeight = compositionLocalOf { 0.dp }

/** Reports this element's height as the page's [BottomChrome] while it is composed. */
@Composable
internal fun Modifier.reportsBottomChrome(): Modifier {
  val chrome = LocalBottomChrome.current ?: return this
  val density = LocalDensity.current
  DisposableEffect(chrome) {
    onDispose { chrome.height = 0.dp }
  }
  return onSizeChanged { chrome.height = with(density) { it.height.toDp() } }
}
