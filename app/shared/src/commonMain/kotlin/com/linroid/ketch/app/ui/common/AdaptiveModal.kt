package com.linroid.ketch.app.ui.common

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.platform.isMobilePlatform

/**
 * A modal surface that adapts to the current platform: a [ModalBottomSheet] on
 * phone/tablet form factors and an [AlertDialog] on desktop and web. Lets
 * feature code declare a modal without branching on platform.
 *
 * On mobile, the bottom-sheet anchor avoids the dialog re-centering that makes
 * `AlertDialog` jump when its content height changes with the soft keyboard up
 * (see issue #135).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveModal(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  dismissButton: (@Composable () -> Unit)? = null,
  title: (@Composable () -> Unit)? = null,
  contentSpacing: Dp = 12.dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  if (isMobilePlatform) {
    val sheetState = rememberModalBottomSheetState(
      skipPartiallyExpanded = true,
    )
    ModalBottomSheet(
      onDismissRequest = onDismissRequest,
      sheetState = sheetState,
      containerColor = KetchTheme.colors.surface,
      modifier = modifier,
    ) {
      Column(
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(contentSpacing),
      ) {
        if (title != null) {
          ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
            title()
          }
        }
        Column(
          modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(contentSpacing),
          content = content,
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (dismissButton != null) {
            dismissButton()
            Spacer(Modifier.width(8.dp))
          }
          confirmButton()
        }
      }
    }
  } else {
    AlertDialog(
      onDismissRequest = onDismissRequest,
      title = title,
      containerColor = KetchTheme.colors.surface,
      tonalElevation = 0.dp,
      shape = RoundedCornerShape(20.dp),
      text = {
        Column(
          modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(contentSpacing),
          content = content,
        )
      },
      confirmButton = confirmButton,
      dismissButton = dismissButton,
      modifier = modifier,
    )
  }
}
