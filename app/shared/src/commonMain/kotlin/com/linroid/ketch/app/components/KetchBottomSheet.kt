package com.linroid.ketch.app.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.theme.KetchTheme

/**
 * The state of a [KetchBottomSheet] that opens fully expanded and has no half-open stop.
 *
 * @param confirmValueChange whether the sheet may move to a value, such as Hidden; the state is
 *   keyed on it, so pass the same lambda on every composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberKetchSheetState(
  confirmValueChange: (SheetValue) -> Boolean = { true },
): SheetState = rememberBottomSheetState(
  initialValue = SheetValue.Hidden,
  enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
  confirmValueChange = confirmValueChange,
)

/**
 * A modal bottom sheet on the raised surface, with rounded top corners, over the scrim; menus,
 * popovers and dialogs open as one on phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KetchBottomSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  sheetState: SheetState = rememberKetchSheetState(),
  properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    sheetState = sheetState,
    shape = KetchTheme.shapes.sheetTop,
    containerColor = colors.surfaceRaised,
    contentColor = colors.textPrimary,
    scrimColor = colors.scrim,
    properties = properties,
    content = content,
  )
}
