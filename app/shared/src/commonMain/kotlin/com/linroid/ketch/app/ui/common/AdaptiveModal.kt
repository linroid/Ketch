package com.linroid.ketch.app.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass
import com.linroid.ketch.app.components.KetchDialogDefaults
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface

/** The form an [AdaptiveModal] takes. */
internal enum class ModalForm {
  /** A bottom sheet, for compact windows. */
  Sheet,

  /** A dialog held near the top of the window, so the soft keyboard never moves it. */
  AnchoredDialog,

  /** A dialog in the middle of the window. */
  CenteredDialog,
}

/**
 * The form of a modal in a window [windowWidth] wide: a sheet below the medium breakpoint,
 * otherwise a dialog, anchored near the top on [touch] platforms.
 */
internal fun modalForm(windowWidth: Dp, touch: Boolean): ModalForm = when {
  windowWidth.value < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND -> ModalForm.Sheet
  touch -> ModalForm.AnchoredDialog
  else -> ModalForm.CenteredDialog
}

/**
 * A modal that adapts to the window: a bottom sheet in compact windows, otherwise a dialog on
 * the raised surface with the title above and the buttons right-aligned below.
 *
 * The form follows the window's width, not the platform. On touch platforms the dialog sits
 * 15% from the top instead of centered, so it does not jump when the soft keyboard changes the
 * space below it (issue #135).
 *
 * @param confirmButton the Primary or Danger button, last in the footer.
 * @param dismissButton the Secondary button before it.
 * @param dismissible whether Esc, Back, a click outside or a swipe may close it; turn it off
 *   while the content holds typed input that would be lost.
 * @param maxWidth widest the dialog grows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveModal(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  dismissButton: (@Composable () -> Unit)? = null,
  title: (@Composable () -> Unit)? = null,
  contentSpacing: Dp = KetchTheme.spacing.s3,
  dismissible: Boolean = true,
  maxWidth: Dp = KetchDialogDefaults.MaxWidth,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val window = LocalWindowInfo.current.containerSize
  val density = LocalDensity.current
  val width = with(density) { window.width.toDp() }
  val height = with(density) { window.height.toDp() }
  val form = modalForm(width, isMobilePlatform)
  val currentDismissible by rememberUpdatedState(dismissible)
  val currentOnDismiss by rememberUpdatedState(onDismissRequest)
  if (form == ModalForm.Sheet) {
    // The sheet state is keyed on this lambda, so it must not change with [dismissible].
    val confirmChange = remember {
      { value: SheetValue -> currentDismissible || value != SheetValue.Hidden }
    }
    val sheetState = rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = SheetValues,
      confirmValueChange = confirmChange,
    )
    ModalBottomSheet(
      onDismissRequest = onDismissRequest,
      modifier = modifier,
      sheetState = sheetState,
      shape = KetchTheme.shapes.sheetTop,
      containerColor = colors.surfaceRaised,
      contentColor = colors.textPrimary,
      scrimColor = colors.scrim,
      properties = ModalBottomSheetProperties(
        shouldDismissOnBackPress = dismissible,
        shouldDismissOnClickOutside = dismissible,
      ),
    ) {
      ModalBody(
        title = title,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        contentSpacing = contentSpacing,
        modifier = Modifier
          .padding(horizontal = KetchTheme.density.pagePadding)
          .padding(bottom = spacing.s4),
        content = content,
      )
    }
    return
  }
  val properties = DialogProperties(
    dismissOnBackPress = dismissible,
    dismissOnClickOutside = dismissible,
    usePlatformDefaultWidth = false,
  )
  Dialog(onDismissRequest = onDismissRequest, properties = properties) {
    if (form == ModalForm.CenteredDialog) {
      DialogPanel(
        title = title,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        contentSpacing = contentSpacing,
        maxWidth = maxWidth,
        modifier = Modifier.padding(vertical = spacing.s6).then(modifier),
        content = content,
      )
      return@Dialog
    }
    // The content fills the window, so outside taps land here rather than on the scrim.
    Box(
      contentAlignment = Alignment.TopCenter,
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
          detectTapGestures { if (currentDismissible) currentOnDismiss() }
        },
    ) {
      DialogPanel(
        title = title,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        contentSpacing = contentSpacing,
        maxWidth = maxWidth,
        modifier = Modifier
          .padding(top = height * TOUCH_ANCHOR_FRACTION, bottom = spacing.s4)
          .pointerInput(Unit) { detectTapGestures {} }
          .then(modifier),
        content = content,
      )
    }
  }
}

/** The dialog form of an [AdaptiveModal], drawn in place; previews show it without a window. */
@Composable
internal fun DialogPanel(
  title: (@Composable () -> Unit)?,
  confirmButton: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  dismissButton: (@Composable () -> Unit)? = null,
  contentSpacing: Dp = KetchTheme.spacing.s3,
  maxWidth: Dp = KetchDialogDefaults.MaxWidth,
  content: @Composable ColumnScope.() -> Unit,
) {
  val spacing = KetchTheme.spacing
  ModalBody(
    title = title,
    confirmButton = confirmButton,
    dismissButton = dismissButton,
    contentSpacing = contentSpacing,
    modifier = modifier
      .padding(horizontal = spacing.s4)
      .widthIn(min = KetchDialogDefaults.MinWidth, max = maxWidth)
      .ketchSurface(
        KetchElevationLevel.E4,
        KetchTheme.shapes.dialog,
        KetchTheme.colors.surfaceRaised,
      )
      .padding(spacing.s6),
    content = content,
  )
}

@Composable
private fun ModalBody(
  title: (@Composable () -> Unit)?,
  confirmButton: @Composable () -> Unit,
  dismissButton: (@Composable () -> Unit)?,
  contentSpacing: Dp,
  modifier: Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val spacing = KetchTheme.spacing
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
    if (title != null) {
      ProvideTextStyle(KetchTheme.typography.titleL.copy(color = KetchTheme.colors.textPrimary)) {
        title()
      }
    }
    Column(
      modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(contentSpacing),
      content = content,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      dismissButton?.invoke()
      confirmButton()
    }
  }
}

private const val TOUCH_ANCHOR_FRACTION = 0.15f

@OptIn(ExperimentalMaterial3Api::class)
private val SheetValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
