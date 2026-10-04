package com.linroid.ketch.app.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.theme.KetchTheme

/**
 * A text input without a fill, border or focus ring of its own, for a surface that frames it,
 * such as a chat composer that holds the field and its buttons in one rounded box.
 *
 * It takes a [TextFieldValue], so its owner sees the selection and the text an input method is
 * still composing, as a key handler must before it treats ↩ as a command. With [maxLines] above
 * 1 it grows with its text up to that many lines, then scrolls.
 *
 * @param placeholder shown in the tertiary color while the field is empty.
 * @param mono sets the text in the monospace style, for links.
 * @param textStyle overrides the style [mono] picks; the color is always the primary text's.
 */
@Composable
fun KetchComposerField(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  enabled: Boolean = true,
  minLines: Int = 1,
  maxLines: Int = Int.MAX_VALUE,
  mono: Boolean = false,
  textStyle: TextStyle? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  interactionSource: MutableInteractionSource? = null,
) {
  val style = composerStyle(textStyle, mono)
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    singleLine = maxLines == 1,
    minLines = minLines,
    maxLines = maxLines,
    textStyle = style,
    cursorBrush = SolidColor(KetchTheme.colors.accent),
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    interactionSource = interactionSource,
    modifier = modifier.graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
    decorationBox = { inner ->
      ComposerDecoration(value.text.isEmpty(), placeholder, style, inner)
    },
  )
}

/**
 * [KetchComposerField] for plain text, where nothing needs the selection or the composition.
 */
@Composable
fun KetchComposerField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  enabled: Boolean = true,
  maxLines: Int = 1,
  mono: Boolean = false,
  textStyle: TextStyle? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  interactionSource: MutableInteractionSource? = null,
) {
  val style = composerStyle(textStyle, mono)
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    singleLine = maxLines == 1,
    maxLines = maxLines,
    textStyle = style,
    cursorBrush = SolidColor(KetchTheme.colors.accent),
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    interactionSource = interactionSource,
    modifier = modifier.graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
    decorationBox = { inner -> ComposerDecoration(value.isEmpty(), placeholder, style, inner) },
  )
}

/** The style of a [KetchComposerField]: [textStyle], or the body or monospace style. */
@Composable
private fun composerStyle(textStyle: TextStyle?, mono: Boolean): TextStyle {
  val type = KetchTheme.typography
  val base = textStyle ?: if (mono) type.mono else type.body
  return base.copy(color = KetchTheme.colors.textPrimary)
}

/** The placeholder under the text while the field is [empty]. */
@Composable
private fun ComposerDecoration(
  empty: Boolean,
  placeholder: String,
  style: TextStyle,
  inner: @Composable () -> Unit,
) {
  Box {
    if (empty) {
      Text(
        text = placeholder,
        style = style,
        color = KetchTheme.colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    inner()
  }
}
