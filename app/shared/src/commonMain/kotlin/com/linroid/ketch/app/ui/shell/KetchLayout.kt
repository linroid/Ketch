package com.linroid.ketch.app.ui.shell

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchSpacing
import com.linroid.ketch.app.theme.ketchSpacing
import com.linroid.ketch.app.ui.downloads.KetchLayoutInfo
import com.linroid.ketch.app.ui.downloads.LayoutTier

/** How the shell offers its destinations. */
internal enum class ShellNavigation {
  /** The 220 dp sidebar beside the content card, on wide windows. */
  Sidebar,

  /** The 72 dp rail, on medium windows or with the sidebar collapsed. */
  Rail,

  /** The phone's top bar, with a bottom bar when there are three destinations or more. */
  Phone,
}

/**
 * How the shell lays out a window [windowWidth] wide, which the screens inside it adapt to.
 *
 * @property tier width tier of the window.
 * @property navigation how the destinations are offered.
 * @property fullBleed whether the content card fills its area without the inset, the rounded
 *   corners and the wash around it, as in the phone shell. The rail and the sidebar keep the card.
 * @property cardWidth width of the content card.
 */
@Immutable
internal data class KetchLayout(
  val windowWidth: Dp,
  val tier: LayoutTier,
  val navigation: ShellNavigation,
  val fullBleed: Boolean,
  val cardWidth: Dp,
) {
  /** The same layout as the Downloads page reads it. */
  val info: KetchLayoutInfo get() = KetchLayoutInfo.of(windowWidth)

  companion object {
    /** Narrowest content card whose inspector docks beside the list. */
    val DockedInspectorWidth: Dp = 1040.dp

    /** Space a list leaves for the phone's Add button: 56 dp, its 16 dp margin and 16 more. */
    val FabClearance: Dp = 88.dp

    /**
     * The layout of a window [windowWidth] wide: phones below [KetchLayoutInfo.MediumWidth], the
     * rail up to [KetchLayoutInfo.ExpandedWidth] and the sidebar from there, unless
     * [sidebarCollapsed] keeps the rail.
     */
    fun of(
      windowWidth: Dp,
      sidebarCollapsed: Boolean = false,
      spacing: KetchSpacing = ketchSpacing(),
    ): KetchLayout {
      val tier = KetchLayoutInfo.of(windowWidth).tier
      val navigation = when {
        tier == LayoutTier.Compact -> ShellNavigation.Phone
        tier == LayoutTier.Expanded && !sidebarCollapsed -> ShellNavigation.Sidebar
        else -> ShellNavigation.Rail
      }
      val fullBleed = navigation == ShellNavigation.Phone
      val navigationWidth = when (navigation) {
        ShellNavigation.Sidebar -> spacing.sidebarWidth
        ShellNavigation.Rail -> spacing.railWidth
        ShellNavigation.Phone -> 0.dp
      }
      val inset = if (fullBleed) 0.dp else spacing.cardInset
      return KetchLayout(
        windowWidth = windowWidth,
        tier = tier,
        navigation = navigation,
        fullBleed = fullBleed,
        cardWidth = (windowWidth - navigationWidth - inset).coerceAtLeast(0.dp),
      )
    }
  }
}

/** Layout of the shell around the screen below, provided by the app shell. */
internal val LocalKetchLayout = compositionLocalOf { KetchLayout.of(KetchLayoutInfo.ExpandedWidth) }
