package com.linroid.ketch.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DISABLED_ALPHA
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.KetchSwitch
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.elideMiddle
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import kotlinx.coroutines.delay

/** How long typing must pause before a text setting is saved. */
private const val COMMIT_DELAY_MS = 600L

/** Least share of a row its title and description keep before the trailing control wraps. */
private const val MIN_TEXT_SHARE = 0.55f

/**
 * Card of related rows, separated by dividers. The dividers are the card's fill showing between
 * the rows, so a row that is not a [SettingsRow] paints `KetchTheme.colors.surface` itself.
 *
 * @param title short label above the card, set as an eyebrow.
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
  val spacing = KetchTheme.spacing
  Column(
    modifier = modifier.settingsAnchor(title.orEmpty()).fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    if (title != null || action != null) {
      Row(
        modifier = Modifier.fillMaxWidth()
          .heightIn(min = KetchTheme.density.buttonSmall)
          .padding(start = spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        KetchEyebrow(
          text = title.orEmpty(),
          modifier = Modifier.weight(1f),
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        action?.invoke()
      }
    }
    // The gaps between rows let the divider color show through.
    Column(
      modifier = Modifier.fillMaxWidth()
        .ketchSurface(
          level = KetchElevationLevel.E0,
          shape = KetchTheme.shapes.card,
          fill = colors.divider,
          border = colors.hairline,
        ),
      verticalArrangement = Arrangement.spacedBy(HairlineWidth),
      content = content,
    )
    if (footer != null) {
      Text(
        text = footer,
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
        modifier = Modifier.padding(horizontal = spacing.s1),
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
  descriptionColor: Color = KetchTheme.colors.textSecondary,
  enabled: Boolean = true,
  leading: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  content: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    modifier = modifier.settingsAnchor(title).fillMaxWidth()
      .background(colors.surface)
      .heightIn(min = KetchTheme.density.iconButtonTarget + spacing.s4)
      .padding(horizontal = spacing.s4, vertical = spacing.s3),
    verticalArrangement = Arrangement.spacedBy(spacing.s3, Alignment.CenterVertically),
  ) {
    TitleLine(leading = leading, trailing = trailing) {
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
        Text(
          text = title,
          style = KetchTheme.typography.body,
          color = if (enabled) colors.textPrimary else colors.textDisabled,
        )
        if (!description.isNullOrBlank()) {
          Text(
            text = description,
            style = KetchTheme.typography.caption,
            color = if (enabled) descriptionColor else colors.textDisabled,
          )
        }
      }
    }
    content?.invoke(this)
  }
}

/**
 * [leading], [text] and [trailing] in one line, or, when [trailing] would leave the text less
 * than [MIN_TEXT_SHARE] of the row as on phones, with [trailing] under the text. Text that fits
 * on one line beside a [trailing] no wider than half the row keeps it there.
 */
@Composable
private fun TitleLine(
  leading: (@Composable () -> Unit)?,
  trailing: (@Composable () -> Unit)?,
  text: @Composable () -> Unit,
) {
  val gap = KetchTheme.spacing.s3
  val lineGap = KetchTheme.spacing.s2
  Layout(
    contents = listOf(leading ?: {}, text, trailing ?: {}),
  ) { (leadingParts, textParts, trailingParts), constraints ->
    val bounded = constraints.hasBoundedWidth
    val gapPx = gap.roundToPx()
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val start = leadingParts.firstOrNull()?.measure(loose)
    val startWidth = start?.let { it.width + gapPx } ?: 0
    val rest = if (bounded) (loose.maxWidth - startWidth).coerceAtLeast(0) else loose.maxWidth
    val end = trailingParts.firstOrNull()?.measure(loose.copy(maxWidth = rest))
    val endWidth = end?.let { it.width + gapPx } ?: 0
    val textRoom = rest - endWidth
    // Short text beside a control that takes at most half the row stays beside it.
    val fits = endWidth * 2 <= loose.maxWidth &&
      textRoom >= textParts.single().maxIntrinsicWidth(loose.maxHeight)
    val inline = end == null || !bounded || textRoom >= loose.maxWidth * MIN_TEXT_SHARE || fits
    val body = if (bounded) {
      val textWidth = (rest - if (inline) endWidth else 0).coerceAtLeast(0)
      textParts.single().measure(loose.copy(minWidth = textWidth, maxWidth = textWidth))
    } else {
      // Unbounded, as when measured for intrinsics: the text takes its own width.
      textParts.single().measure(loose)
    }
    val width = if (bounded) constraints.maxWidth else startWidth + body.width + endWidth
    val top = maxOf(start?.height ?: 0, body.height, if (inline) end?.height ?: 0 else 0)
    val height = if (end == null || inline) top else top + lineGap.roundToPx() + end.height
    layout(width, height) {
      start?.placeRelative(0, (top - start.height) / 2)
      body.placeRelative(startWidth, (top - body.height) / 2)
      if (end != null) {
        if (inline) {
          end.placeRelative(width - end.width, (top - end.height) / 2)
        } else {
          end.placeRelative(startWidth, top + lineGap.roundToPx())
        }
      }
    }
  }
}

/** A value at the end of a row, such as "English" or a shortcut, for a row that has no control. */
@Composable
internal fun SettingsValue(text: String) {
  Text(
    text = text,
    style = KetchTheme.typography.bodyS,
    color = KetchTheme.colors.textSecondary,
    maxLines = 1,
  )
}

/** Row whose whole surface toggles a switch. */
@Composable
fun SettingsSwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  descriptionColor: Color = KetchTheme.colors.textSecondary,
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
      KetchSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    },
  )
}

/**
 * Row with a compact drop-down button on the right, which lists [options] in a menu with a check
 * on [value].
 */
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
      val colors = KetchTheme.colors
      val spacing = KetchTheme.spacing
      val shape = KetchTheme.shapes.full
      val interactions = remember { MutableInteractionSource() }
      val overlay = rememberInteractionOverlay(interactions, enabled)
      val focus = rememberFocusVisibility()
      var expanded by remember { mutableStateOf(false) }
      Box {
        Row(
          modifier = Modifier
            .focusRing(focus.visible, shape, colors.focusRing)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .height(KetchTheme.density.buttonMedium)
            .widthIn(min = SelectMinWidth)
            .background(colors.surface, shape)
            .background(overlay, shape)
            .border(HairlineWidth, colors.borderStrong, shape)
            .ketchClickable(
              interactions = interactions,
              focus = focus,
              enabled = enabled,
              role = Role.DropdownList,
              onClick = { expanded = true },
            )
            .padding(start = spacing.s3, end = spacing.s2),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.s1, Alignment.End),
        ) {
          Text(
            text = label(value),
            style = KetchTheme.typography.label,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          KetchIconImage(
            icon = KetchIcon.ChevronDown,
            size = KetchTheme.density.controlGlyph,
            tint = colors.textTertiary,
          )
        }
        KetchMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          options.forEach { option ->
            item(
              label = label(option),
              checked = option == value,
              onClick = { if (option != value) onSelect(option) },
            )
          }
        }
      }
    },
  )
}

/** Mutually exclusive [options] on a sliding track, for two to four short choices. */
@Composable
fun <T> SettingsSegmented(
  value: T,
  options: List<T>,
  label: (T) -> String,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  KetchSegmented(
    options = options,
    selected = value,
    onSelect = onSelect,
    label = label,
    modifier = modifier,
  )
}

/**
 * Row with `[−] 3 [+]` on the right, for a count chosen from [values], in order; each press
 * applies at once.
 *
 * @param label how a value reads, such as "Unlimited" for 0.
 * @param noun what is counted, for screen readers, such as "downloads at once".
 */
@Composable
internal fun SettingsStepperRow(
  title: String,
  description: String,
  value: Int,
  values: List<Int>,
  label: (Int) -> String,
  noun: String,
  onChange: (Int) -> Unit,
) {
  SettingsRow(
    title = title,
    description = description,
    trailing = {
      val index = values.indexOf(value)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
        modifier = Modifier.semantics { stateDescription = "${label(value)} $noun" },
      ) {
        StepButton(
          plus = false,
          description = "Fewer $noun",
          enabled = index > 0,
          onClick = { values.getOrNull(index - 1)?.let(onChange) },
        )
        Text(
          text = label(value),
          style = KetchTheme.typography.numeral,
          color = KetchTheme.colors.textPrimary,
          textAlign = TextAlign.Center,
          maxLines = 1,
          modifier = Modifier.widthIn(min = StepperValueMinWidth),
        )
        StepButton(
          plus = true,
          description = "More $noun",
          enabled = index in 0 until values.lastIndex,
          onClick = { values.getOrNull(index + 1)?.let(onChange) },
        )
      }
    },
  )
}

@Composable
private fun StepButton(plus: Boolean, description: String, enabled: Boolean, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.sm
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled)
  val focus = rememberFocusVisibility()
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
      .size(KetchTheme.density.buttonSmall)
      .background(colors.surface, shape)
      .background(overlay, shape)
      .border(HairlineWidth, colors.borderStrong, shape)
      .semantics { contentDescription = description }
      .ketchClickable(interactions, focus, enabled = enabled, onClick = onClick),
  ) {
    val ink = colors.textPrimary
    Canvas(Modifier.size(KetchTheme.density.controlGlyph)) {
      // Drawn like the icon set: a 1.7 unit stroke on a 20 unit grid.
      val unit = size.width / GLYPH_GRID
      val from = GLYPH_START * unit
      val to = (GLYPH_GRID - GLYPH_START) * unit
      val mid = GLYPH_GRID / 2 * unit
      val stroke = GLYPH_STROKE * unit
      drawLine(ink, Offset(from, mid), Offset(to, mid), stroke, StrokeCap.Round)
      if (plus) drawLine(ink, Offset(mid, from), Offset(mid, to), stroke, StrokeCap.Round)
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
  val focusManager = LocalFocusManager.current
  var text by remember { mutableStateOf(value) }
  var revealed by remember { mutableStateOf(false) }
  // Adopt changes made elsewhere, but never rewrite what the user is
  // typing just because saving normalised it.
  LaunchedEffect(value) {
    if (normalize(text) != value) text = value
  }
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

  val showToggle = secret && text.isNotEmpty()
  SettingsTextField(
    value = text,
    onValueChange = { typed ->
      text = when {
        numeric -> typed.filter(Char::isDigit)
        decimal -> typed.filter { it.isDigit() || it == '.' }
        else -> typed
      }
    },
    modifier = modifier,
    placeholder = placeholder,
    error = validate(text),
    onDone = {
      commit()
      focusManager.clearFocus()
    },
    onFocusChange = { focused -> if (!focused) commit() },
    keyboardType = when {
      numeric -> KeyboardType.Number
      decimal -> KeyboardType.Decimal
      secret -> KeyboardType.Password
      else -> KeyboardType.Text
    },
    visualTransformation = if (secret && !revealed) {
      PasswordVisualTransformation()
    } else {
      VisualTransformation.None
    },
    mono = mono,
    enabled = enabled,
    width = width,
    trailing = if (showToggle || actions != null) {
      {
        if (showToggle) {
          KetchButton(
            text = if (revealed) "Hide" else "Show",
            onClick = { revealed = !revealed },
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
            enabled = enabled,
          )
        }
        actions?.invoke(this)
      }
    } else {
      null
    },
  )
}

/**
 * Single-line settings text field that only reports edits, for text the
 * caller acts on itself, e.g. with an Add button. [SettingsTextInput]
 * builds on it for text that saves itself.
 *
 * It looks like `KetchTextField`, and also takes a [visualTransformation] for secrets.
 *
 * @param error shown under the field, whose border turns red.
 * @param onDone runs when Enter is pressed.
 * @param onFocusChange called when the field gains or loses focus.
 * @param width fixed width; the field fills the row without one.
 * @param trailing controls at the end of the field.
 */
@Composable
fun SettingsTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  error: String? = null,
  onDone: () -> Unit = {},
  onFocusChange: (Boolean) -> Unit = {},
  keyboardType: KeyboardType = KeyboardType.Text,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  mono: Boolean = false,
  enabled: Boolean = true,
  width: Dp? = null,
  trailing: (@Composable RowScope.() -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.textField
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  var focused by remember { mutableStateOf(false) }
  val failed = colors.status.failed.color
  val border by animateColorAsState(
    targetValue = when {
      error != null -> failed
      focused -> colors.accent
      hovered && enabled -> colors.textTertiary
      else -> colors.borderStrong
    },
    animationSpec = tween(KetchTheme.motion.micro),
  )
  val textStyle = (if (mono) KetchTheme.typography.mono else KetchTheme.typography.bodyS)
    .copy(color = if (enabled) colors.textPrimary else colors.textDisabled)
  val errorText = error
  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      singleLine = true,
      textStyle = textStyle,
      cursorBrush = SolidColor(colors.accent),
      visualTransformation = visualTransformation,
      interactionSource = interactions,
      keyboardOptions = KeyboardOptions(
        keyboardType = keyboardType,
        imeAction = ImeAction.Done,
        autoCorrectEnabled = false,
      ),
      keyboardActions = KeyboardActions(onDone = { onDone() }),
      modifier = Modifier
        .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
        .onFocusChanged {
          if (it.isFocused != focused) {
            focused = it.isFocused
            onFocusChange(it.isFocused)
          }
        }
        .focusRing(
          visible = focused,
          shape = shape,
          color = (if (error != null) failed else colors.accent).copy(alpha = FIELD_RING_ALPHA),
          gap = 0.dp,
          width = spacing.s0_5,
        )
        .heightIn(min = KetchTheme.density.input)
        .background(if (enabled) colors.surfaceSunken else colors.surfaceHover, shape)
        .border(HairlineWidth, border, shape)
        .semantics { if (errorText != null) error(errorText) },
      decorationBox = { inner ->
        Row(
          modifier = Modifier
            .heightIn(min = KetchTheme.density.input)
            .padding(start = spacing.s3, end = if (trailing != null) spacing.s1 else spacing.s3),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.s1),
        ) {
          Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f).padding(vertical = spacing.s1),
          ) {
            if (value.isEmpty()) {
              Text(
                text = placeholder,
                style = textStyle,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            inner()
          }
          trailing?.invoke(this)
        }
      },
    )
    if (error != null) {
      Text(text = error, style = KetchTheme.typography.caption, color = failed)
    }
  }
}

/** Tone of a [SettingsNotice]. */
enum class NoticeTone { Info, Success, Warning, Error }

/** Inline status message on a soft fill, optionally with an action button. */
@Composable
fun SettingsNotice(
  text: String,
  tone: NoticeTone,
  modifier: Modifier = Modifier,
  action: (@Composable () -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val (fill, accent) = when (tone) {
    NoticeTone.Info -> colors.surfaceSunken to colors.textSecondary
    NoticeTone.Success -> colors.status.completed.soft to colors.status.completed.color
    NoticeTone.Warning -> colors.status.paused.soft to colors.status.paused.color
    NoticeTone.Error -> colors.status.failed.soft to colors.status.failed.color
  }
  val icon = when (tone) {
    NoticeTone.Info -> KetchIcon.Info
    NoticeTone.Success -> KetchIcon.CheckCircle
    NoticeTone.Warning, NoticeTone.Error -> KetchIcon.Warning
  }
  Row(
    modifier = modifier.fillMaxWidth()
      .background(fill, KetchTheme.shapes.md)
      .padding(horizontal = spacing.s3, vertical = spacing.s2),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    KetchIconImage(icon = icon, size = KetchTheme.density.controlGlyph, tint = accent)
    Text(
      text = text,
      style = KetchTheme.typography.bodyS,
      color = if (tone == NoticeTone.Info) colors.textSecondary else colors.textPrimary,
      modifier = Modifier.weight(1f).padding(vertical = spacing.s1),
    )
    action?.invoke()
  }
}

/**
 * One line of [text] whose middle gives way to "…" when it is too wide, as for paths, whose
 * folder name sits at the end.
 */
@Composable
internal fun MiddleEllipsisText(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
) {
  val measurer = rememberTextMeasurer()
  BoxWithConstraints(modifier) {
    val maxWidth = constraints.maxWidth
    val shown = remember(text, style, maxWidth) {
      if (!constraints.hasBoundedWidth) {
        text
      } else {
        elideMiddle(text) {
          measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <= maxWidth
        }
      }
    }
    Text(text = shown, style = style, color = color, maxLines = 1, softWrap = false)
  }
}

/** A small spinner beside [text], while a device's settings load. */
@Composable
internal fun SettingsLoading(text: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
    modifier = Modifier.padding(horizontal = KetchTheme.spacing.s1),
  ) {
    KetchSpinner()
    Text(text, style = KetchTheme.typography.bodyS, color = KetchTheme.colors.textSecondary)
  }
}

/** Shown in place of a device page while no device is connected. */
@Composable
internal fun NoDeviceNotice() {
  SettingsNotice(
    text = "Connect to a device to change its settings.",
    tone = NoticeTone.Info,
  )
}

/**
 * The error notice of a device page: what failed, with Retry while nothing has loaded yet.
 *
 * @param loaded whether the page has settings to show despite the error.
 */
@Composable
internal fun DeviceSettingsError(error: String?, loaded: Boolean, onRetry: () -> Unit) {
  if (error == null) return
  SettingsNotice(
    text = if (loaded) error else "Couldn't load the settings: $error",
    tone = NoticeTone.Error,
    action = if (loaded) {
      null
    } else {
      {
        KetchButton(
          text = "Retry",
          onClick = onRetry,
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
        )
      }
    },
  )
}

private const val FIELD_RING_ALPHA = 0.2f
private const val GLYPH_GRID = 20f
private const val GLYPH_START = 4f
private const val GLYPH_STROKE = 1.7f

/** Width of the borders and dividers of settings controls. */
internal val HairlineWidth = 1.dp
private val SelectMinWidth = 96.dp
private val StepperValueMinWidth = 72.dp
