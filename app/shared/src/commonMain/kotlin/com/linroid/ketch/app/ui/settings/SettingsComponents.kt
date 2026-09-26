package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.delay

/** How long typing must pause before a text setting is saved. */
private const val COMMIT_DELAY_MS = 600L

/** Title and one-line explanation at the top of a settings category. */
@Composable
fun SettingsHeader(title: String, description: String) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = title,
      style = KetchTheme.typography.displaySmall,
      color = KetchTheme.colors.onBackground,
    )
    Text(
      text = description,
      style = KetchTheme.typography.bodyMedium,
      color = KetchTheme.colors.onSurfaceVariant,
    )
  }
}

/**
 * Card of related rows, separated by hairlines.
 *
 * @param title short label above the card.
 * @param footer note under the card that applies to all of its rows.
 * @param action optional control at the end of the title line.
 */
@Composable
fun SettingsGroup(
  modifier: Modifier = Modifier,
  title: String? = null,
  footer: String? = null,
  action: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  val shape = RoundedCornerShape(10.dp)
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (title != null || action != null) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = title.orEmpty().uppercase(),
          style = KetchTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
          ),
          color = colors.onSurfaceDim,
          modifier = Modifier.weight(1f),
        )
        action?.invoke()
      }
    }
    // The gaps between rows let the hairline colour show through.
    Column(
      modifier = Modifier.fillMaxWidth()
        .clip(shape)
        .background(colors.outlineVariant)
        .border(1.dp, colors.outline, shape),
      verticalArrangement = Arrangement.spacedBy(1.dp),
      content = content,
    )
    if (footer != null) {
      Text(
        text = footer,
        style = KetchTheme.typography.bodySmall,
        color = colors.onSurfaceDim,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
  }
}

/**
 * One setting inside a [SettingsGroup]: title and description on the
 * left, a compact [trailing] control on the right, and optional
 * full-width [content] (e.g. a text field) underneath.
 */
@Composable
fun SettingsRow(
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  descriptionColor: Color = KetchTheme.colors.onSurfaceVariant,
  enabled: Boolean = true,
  leading: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  content: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  Column(
    modifier = modifier.fillMaxWidth()
      .background(colors.surface)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      leading?.invoke()
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = title,
          style = KetchTheme.typography.bodyLarge,
          color = if (enabled) colors.onBackground else colors.onSurfaceDim,
        )
        if (!description.isNullOrBlank()) {
          Text(
            text = description,
            style = KetchTheme.typography.bodySmall,
            color = if (enabled) descriptionColor else colors.onSurfaceDim,
          )
        }
      }
      trailing?.invoke()
    }
    content?.invoke(this)
  }
}

/** Row whose whole surface toggles a switch. */
@Composable
fun SettingsSwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  descriptionColor: Color = KetchTheme.colors.onSurfaceVariant,
  enabled: Boolean = true,
) {
  SettingsRow(
    title = title,
    description = description,
    descriptionColor = descriptionColor,
    enabled = enabled,
    modifier = modifier.toggleable(
      value = checked,
      enabled = enabled,
      role = Role.Switch,
      onValueChange = onCheckedChange,
    ),
    trailing = {
      Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    },
  )
}

/** Row with a drop-down of [options] on the right. */
@Composable
fun <T> SettingsSelectRow(
  title: String,
  value: T,
  options: List<T>,
  label: (T) -> String,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  enabled: Boolean = true,
) {
  SettingsRow(
    title = title,
    description = description,
    enabled = enabled,
    modifier = modifier,
    trailing = {
      SettingsSelect(
        value = value,
        options = options,
        label = label,
        onSelect = onSelect,
        enabled = enabled,
      )
    },
  )
}

/** Compact drop-down button listing [options], with a tick on [value]. */
@Composable
fun <T> SettingsSelect(
  value: T,
  options: List<T>,
  label: (T) -> String,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val colors = KetchTheme.colors
  val shape = RoundedCornerShape(8.dp)
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  var expanded by remember { mutableStateOf(false) }
  Box(modifier) {
    Row(
      modifier = Modifier.height(36.dp)
        .widthIn(min = 96.dp)
        .clip(shape)
        .background(if (hovered && enabled) colors.surfaceHover else colors.surface)
        .border(1.dp, colors.outline, shape)
        .hoverable(interactions)
        .clickable(
          interactionSource = interactions,
          indication = androidx.compose.foundation.LocalIndication.current,
          enabled = enabled,
          role = Role.DropdownList,
          onClick = { expanded = true },
        )
        .padding(start = 12.dp, end = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = label(value),
        style = KetchTheme.typography.labelLarge,
        color = if (enabled) colors.onBackground else colors.onSurfaceDim,
        modifier = Modifier.padding(end = 8.dp),
        maxLines = 1,
      )
      KetchIconImage(
        icon = KetchIcon.ChevronDown,
        size = 14.dp,
        tint = colors.onSurfaceDim,
      )
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      shape = RoundedCornerShape(10.dp),
      containerColor = colors.surface,
      border = BorderStroke(1.dp, colors.outline),
    ) {
      options.forEach { option ->
        val selected = option == value
        DropdownMenuItem(
          text = {
            Text(
              text = label(option),
              style = KetchTheme.typography.bodyMedium,
              color = if (selected) colors.primary else colors.onBackground,
            )
          },
          trailingIcon = if (selected) {
            { KetchIconImage(KetchIcon.Check, size = 16.dp, tint = colors.primary) }
          } else {
            null
          },
          onClick = {
            expanded = false
            if (!selected) onSelect(option)
          },
        )
      }
    }
  }
}

/** Pill of mutually exclusive [options], for two to four short choices. */
@Composable
fun <T> SettingsSegmented(
  value: T,
  options: List<T>,
  label: (T) -> String,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  Row(
    modifier = modifier.clip(RoundedCornerShape(9.dp))
      .background(colors.surfaceVariant)
      .border(1.dp, colors.outlineVariant, RoundedCornerShape(9.dp))
      .padding(3.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    options.forEach { option ->
      val selected = option == value
      val shape = RoundedCornerShape(6.dp)
      Box(
        modifier = Modifier.height(28.dp)
          .clip(shape)
          .background(if (selected) colors.surface else Color.Transparent)
          .let { if (selected) it.border(1.dp, colors.outline, shape) else it }
          .selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = { onSelect(option) },
          )
          .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label(option),
          style = KetchTheme.typography.labelMedium,
          color = if (selected) colors.onBackground else colors.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * Text setting that saves itself: shortly after typing pauses, when
 * focus leaves, on Enter, and when the field leaves the screen — but
 * only while [validate] accepts the text.
 *
 * @param value saved value.
 * @param onCommit saves a changed, valid value.
 * @param normalize turns typed text into the form that is saved, e.g.
 *   trimmed. The field keeps what was typed while it matches [value].
 * @param validate returns why the text cannot be saved, or `null`.
 * @param secret masks the text, with a Show/Hide toggle.
 * @param numeric accepts digits only.
 * @param decimal accepts digits and a decimal point.
 */
@Composable
fun SettingsTextInput(
  value: String,
  onCommit: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  normalize: (String) -> String = { it.trim() },
  validate: (String) -> String? = { null },
  secret: Boolean = false,
  numeric: Boolean = false,
  decimal: Boolean = false,
  mono: Boolean = false,
  enabled: Boolean = true,
  width: Dp? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val focusManager = LocalFocusManager.current
  var text by remember { mutableStateOf(value) }
  var focused by remember { mutableStateOf(false) }
  var revealed by remember { mutableStateOf(false) }
  // Adopt changes made elsewhere, but never rewrite what the user is
  // typing just because saving normalised it.
  LaunchedEffect(value) {
    if (normalize(text) != value) text = value
  }
  val error = validate(text)
  val commit by rememberUpdatedState {
    val normalized = normalize(text)
    if (normalized != value && validate(text) == null) onCommit(normalized)
  }
  LaunchedEffect(text) {
    delay(COMMIT_DELAY_MS)
    commit()
  }
  DisposableEffect(Unit) {
    onDispose { commit() }
  }

  val shape = RoundedCornerShape(10.dp)
  val borderColor = when {
    error != null -> colors.error
    focused -> colors.primary
    else -> colors.outline
  }
  val textStyle = (if (mono) KetchTheme.typography.monoSmall else KetchTheme.typography.bodyMedium)
    .copy(color = if (enabled) colors.onBackground else colors.onSurfaceDim)
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicTextField(
      value = text,
      onValueChange = { typed ->
        text = when {
          numeric -> typed.filter(Char::isDigit)
          decimal -> typed.filter { it.isDigit() || it == '.' }
          else -> typed
        }
      },
      enabled = enabled,
      singleLine = true,
      textStyle = textStyle,
      cursorBrush = SolidColor(colors.primary),
      visualTransformation = if (secret && !revealed) {
        PasswordVisualTransformation()
      } else {
        VisualTransformation.None
      },
      keyboardOptions = KeyboardOptions(
        keyboardType = when {
          numeric -> KeyboardType.Number
          decimal -> KeyboardType.Decimal
          secret -> KeyboardType.Password
          else -> KeyboardType.Text
        },
        imeAction = ImeAction.Done,
        autoCorrectEnabled = false,
      ),
      keyboardActions = KeyboardActions(onDone = {
        commit()
        focusManager.clearFocus()
      }),
      modifier = Modifier
        .let { if (width != null) it.widthIn(max = width) else it.fillMaxWidth() }
        .onFocusChanged {
          if (focused && !it.isFocused) commit()
          focused = it.isFocused
        }
        .defaultMinSize(minHeight = 40.dp)
        .clip(shape)
        .background(if (enabled) colors.background else colors.surfaceVariant)
        .border(if (focused || error != null) 2.dp else 1.dp, borderColor, shape),
      decorationBox = { inner ->
        Row(
          modifier = Modifier.padding(start = 12.dp, end = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (text.isEmpty()) {
              Text(placeholder, style = textStyle, color = colors.onSurfaceDim, maxLines = 1)
            }
            inner()
          }
          if (secret && text.isNotEmpty()) {
            KetchButton(
              text = if (revealed) "Hide" else "Show",
              onClick = { revealed = !revealed },
              variant = KetchButtonVariant.Ghost,
              size = KetchButtonSize.Small,
              enabled = enabled,
            )
          }
          actions?.invoke(this)
          if (actions == null && !(secret && text.isNotEmpty())) {
            Box(Modifier.padding(end = 8.dp))
          }
        }
      },
    )
    if (error != null) {
      Text(text = error, style = KetchTheme.typography.bodySmall, color = colors.error)
    }
  }
}

/** Tone of a [SettingsNotice]. */
enum class NoticeTone { Info, Success, Warning, Error }

/** Inline status message, optionally with an action button. */
@Composable
fun SettingsNotice(
  text: String,
  tone: NoticeTone,
  modifier: Modifier = Modifier,
  action: (@Composable () -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val accent = when (tone) {
    NoticeTone.Info -> colors.onSurfaceVariant
    NoticeTone.Success -> colors.success
    NoticeTone.Warning -> colors.warning
    NoticeTone.Error -> colors.error
  }
  val shape = RoundedCornerShape(10.dp)
  Row(
    modifier = modifier.fillMaxWidth()
      .clip(shape)
      .background(if (tone == NoticeTone.Info) colors.surfaceVariant else accent.copy(alpha = 0.1f))
      .border(1.dp, accent.copy(alpha = 0.25f), shape)
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = text,
      style = KetchTheme.typography.bodySmall,
      color = if (tone == NoticeTone.Info) colors.onSurfaceVariant else accent,
      modifier = Modifier.weight(1f),
    )
    action?.invoke()
  }
}
