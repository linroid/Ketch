package com.linroid.ketch.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Text input on a sunken fill, 32 dp tall with a pointer and 48 dp on touch.
 *
 * The [label] sits above the field and never floats; [error] replaces the field's border color
 * and appears below it. Focus draws a 1 dp accent border with a soft 3 dp ring. With [maxLines]
 * above 1 it grows with its text up to that many lines, then scrolls.
 *
 * @param mono sets the text in the monospace style, for links and paths.
 * @param clearable shows a clear button while there is text.
 * @param onPaste shows a Paste action at the end that runs it.
 * @param trailing extra content at the end of the field, such as a unit toggle.
 * @param textStyle overrides the text style picked by [mono].
 * @param onFocusChange runs when the field gains or loses focus, for commit-on-blur.
 * @param visualTransformation changes how the text shows, such as masking a password.
 */
@Composable
fun KetchTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  leadingIcon: KetchIcon? = null,
  mono: Boolean = false,
  enabled: Boolean = true,
  label: String? = null,
  error: String? = null,
  clearable: Boolean = true,
  onPaste: (() -> Unit)? = null,
  trailing: (@Composable RowScope.() -> Unit)? = null,
  maxLines: Int = 1,
  textStyle: TextStyle? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  onFocusChange: (Boolean) -> Unit = {},
  visualTransformation: VisualTransformation = VisualTransformation.None,
) {
  val interactions = remember { MutableInteractionSource() }
  val type = KetchTheme.typography
  val style = (textStyle ?: if (mono) type.mono else type.bodyS)
    .copy(color = KetchTheme.colors.textPrimary)
  FieldFrame(
    modifier = modifier,
    label = label,
    error = error,
    enabled = enabled,
    interactions = interactions,
    onFocusChange = onFocusChange,
  ) { fieldModifier ->
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
      visualTransformation = visualTransformation,
      interactionSource = interactions,
      modifier = fieldModifier,
      decorationBox = { inner ->
        FieldDecoration(
          isEmpty = value.isEmpty(),
          placeholder = placeholder,
          textStyle = style,
          leadingIcon = leadingIcon,
          multiline = maxLines > 1,
          onClear = if (clearable && enabled && value.isNotEmpty()) {
            { onValueChange("") }
          } else {
            null
          },
          onPaste = onPaste.takeIf { enabled },
          trailing = trailing,
          inner = inner,
        )
      },
    )
  }
}

/** Label above, the bordered field, and the error below. */
@Composable
private fun FieldFrame(
  modifier: Modifier,
  label: String?,
  error: String?,
  enabled: Boolean,
  interactions: MutableInteractionSource,
  onFocusChange: (Boolean) -> Unit,
  field: @Composable (Modifier) -> Unit,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val shape = KetchTheme.shapes.textField
  val errorText = error
  // Read from the focus system, not the interactions: a field focused in the frame it appears
  // in would emit its focus before anyone collects it.
  var focused by remember { mutableStateOf(false) }
  val hovered by interactions.collectIsHoveredAsState()
  val focusCallback by rememberUpdatedState(onFocusChange)
  val border by animateColorAsState(
    targetValue = when {
      error != null -> colors.status.failed.color
      focused -> colors.accent
      hovered && enabled -> colors.textTertiary
      else -> colors.borderStrong
    },
    animationSpec = tween(KetchTheme.motion.micro),
  )
  Column(
    modifier = modifier.graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
    verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
  ) {
    if (label != null) Text(label, style = type.labelS, color = colors.textSecondary)
    field(
      Modifier
        .onFocusChanged {
          if (it.isFocused != focused) {
            focused = it.isFocused
            focusCallback(it.isFocused)
          }
        }
        .focusRing(
          visible = focused,
          shape = shape,
          color = (if (error != null) colors.status.failed.color else colors.accent)
            .copy(alpha = FIELD_RING_ALPHA),
          gap = 0.dp,
          width = FieldRingWidth,
        )
        .fillMaxWidth()
        .heightIn(min = KetchTheme.density.input)
        .background(colors.surfaceSunken, shape)
        .border(1.dp, border, shape)
        .semantics { if (errorText != null) error(errorText) },
    )
    if (error != null) Text(error, style = type.caption, color = colors.status.failed.color)
  }
}

@Composable
private fun FieldDecoration(
  isEmpty: Boolean,
  placeholder: String,
  textStyle: TextStyle,
  leadingIcon: KetchIcon?,
  multiline: Boolean,
  onClear: (() -> Unit)?,
  onPaste: (() -> Unit)?,
  trailing: (@Composable RowScope.() -> Unit)?,
  inner: @Composable () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val hasEnd = onClear != null || onPaste != null || trailing != null
  Row(
    verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .heightIn(min = KetchTheme.density.input)
      .padding(start = spacing.s3, end = if (hasEnd) spacing.s1 else spacing.s3),
  ) {
    if (leadingIcon != null) {
      KetchIconImage(
        icon = leadingIcon,
        size = KetchTheme.density.controlGlyph,
        tint = colors.textTertiary,
        modifier = if (multiline) Modifier.padding(top = spacing.s2) else Modifier,
      )
    }
    Box(
      contentAlignment = Alignment.CenterStart,
      modifier = Modifier
        .weight(1f)
        .padding(vertical = if (multiline) spacing.s2 else spacing.s1),
    ) {
      if (isEmpty) Text(placeholder, style = textStyle, color = colors.textTertiary, maxLines = 1)
      inner()
    }
    if (onPaste != null) FieldAction("Paste", onPaste)
    if (onClear != null) {
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = "Clear text",
        size = KetchButtonSize.Small,
        onClick = onClear,
      )
    }
    trailing?.invoke(this)
  }
}

/** Small text action inside a field, such as Paste. */
@Composable
private fun FieldAction(text: String, onClick: () -> Unit) {
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val shape = KetchTheme.shapes.xs
  Text(
    text = text,
    style = KetchTheme.typography.labelS,
    color = KetchTheme.colors.accentText,
    modifier = Modifier
      .background(overlay, shape)
      .ketchClickable(interactions, onClick = onClick)
      .padding(horizontal = KetchTheme.spacing.s2, vertical = KetchTheme.spacing.s1),
  )
}

private const val FIELD_RING_ALPHA = 0.2f
private val FieldRingWidth = 3.dp
