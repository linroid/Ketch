package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.SuggestedCategory
import com.linroid.ketch.app.state.categorySummary
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.isMimeTypeRule
import com.linroid.ketch.app.state.newCategoryFolder
import com.linroid.ketch.app.state.normalizeCategoryFolder
import com.linroid.ketch.app.state.parseExtensions
import com.linroid.ketch.app.state.parseHosts
import com.linroid.ketch.app.state.parseMimeTypes
import com.linroid.ketch.app.state.ruleText
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.settings_action_hide
import ketch.app.shared.generated.resources.settings_categories
import ketch.app.shared.generated.resources.settings_categories_add
import ketch.app.shared.generated.resources.settings_categories_empty
import ketch.app.shared.generated.resources.settings_categories_empty_hint
import ketch.app.shared.generated.resources.settings_categories_extensions
import ketch.app.shared.generated.resources.settings_categories_extensions_placeholder
import ketch.app.shared.generated.resources.settings_categories_folder
import ketch.app.shared.generated.resources.settings_categories_folder_hint
import ketch.app.shared.generated.resources.settings_categories_folder_invalid
import ketch.app.shared.generated.resources.settings_categories_footer
import ketch.app.shared.generated.resources.settings_categories_move_down
import ketch.app.shared.generated.resources.settings_categories_move_up
import ketch.app.shared.generated.resources.settings_categories_new_folder
import ketch.app.shared.generated.resources.settings_categories_remove
import ketch.app.shared.generated.resources.settings_categories_sites
import ketch.app.shared.generated.resources.settings_categories_sites_hint
import ketch.app.shared.generated.resources.settings_categories_sites_placeholder
import ketch.app.shared.generated.resources.settings_categories_suggested
import ketch.app.shared.generated.resources.settings_categories_types
import ketch.app.shared.generated.resources.settings_categories_types_hint
import ketch.app.shared.generated.resources.settings_categories_types_invalid
import ketch.app.shared.generated.resources.settings_categories_types_placeholder
import ketch.app.shared.generated.resources.settings_categories_unsupported
import org.jetbrains.compose.resources.stringResource

/**
 * The category folders of [device]: rules that sort the downloads which choose no folder into
 * folders inside its download folder, a list whose first match wins. One category at a time
 * opens to edit its folder and rules; every change applies as it is made, through [onChange].
 *
 * @param current the configuration as last changed, which edits build on, so that a change
 *   made before the page shows the previous one is kept.
 * @param supported whether [device] runs a Ketch that sorts downloads into category folders.
 */
@Composable
internal fun CategoryGroup(
  device: InstanceEntry,
  current: () -> DownloadConfig,
  supported: Boolean,
  onChange: (DownloadConfig) -> Unit,
) {
  val categories = current().categories
  var open by rememberSaveable(device.deviceId) { mutableStateOf(-1) }
  val newFolder = stringResource(Res.string.settings_categories_new_folder)
  val suggestedFolders = SuggestedCategory.entries.map { stringResource(it.folder) }
  val save = { list: List<DownloadCategory> -> onChange(current().copy(categories = list)) }
  // A row's fields may commit after it moved or closed, so changes find their category again.
  val update = { original: DownloadCategory, changed: DownloadCategory ->
    val list = current().categories
    val at = list.find(original)
    if (at >= 0) save(list.toMutableList().also { it[at] = changed })
  }
  val moveBy = { category: DownloadCategory, offset: Int ->
    val list = current().categories.toMutableList()
    val from = list.find(category)
    val to = from + offset
    if (from >= 0 && to in list.indices) {
      list.add(to, list.removeAt(from))
      save(list)
      open = to
    }
  }
  SettingsGroup(
    title = stringResource(Res.string.settings_categories),
    footer = stringResource(Res.string.settings_categories_footer).takeIf { supported },
    action = if (supported) {
      {
        KetchButton(
          text = stringResource(Res.string.settings_categories_add),
          onClick = {
            val list = current().categories
            save(list + DownloadCategory(folder = newCategoryFolder(newFolder, list)))
            open = list.size
          },
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Plus,
        )
      }
    } else {
      null
    },
  ) {
    when {
      !supported -> SettingsRow(
        title = stringResource(Res.string.settings_categories_unsupported, device.label),
      )
      categories.isEmpty() -> SettingsRow(
        title = stringResource(Res.string.settings_categories_empty),
        description = stringResource(Res.string.settings_categories_empty_hint),
        trailing = {
          KetchButton(
            text = stringResource(Res.string.settings_categories_suggested),
            onClick = {
              save(SuggestedCategory.entries.zip(suggestedFolders, SuggestedCategory::toCategory))
            },
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
          )
        },
      )
      else -> categories.forEachIndexed { index, category ->
        CategoryRow(
          category = category,
          open = open == index,
          onToggle = { open = if (open == index) -1 else index },
          onChange = { changed -> update(category, changed) },
          onMoveUp = if (index > 0) ({ moveBy(category, -1) }) else null,
          onMoveDown = if (index < categories.lastIndex) ({ moveBy(category, 1) }) else null,
          onRemove = {
            val list = current().categories
            val at = list.find(category)
            if (at >= 0) save(list.filterIndexed { i, _ -> i != at })
            open = -1
          },
        )
      }
    }
  }
}

/**
 * Where [category] is in this list: the very instance a row showed, or else an equal one, as
 * after the device sent its settings back; -1 when it is gone.
 */
private fun List<DownloadCategory>.find(category: DownloadCategory): Int =
  indexOfFirst { it === category }.takeIf { it >= 0 } ?: indexOf(category)

/**
 * A category: its type icon, folder and rules in short, and, while [open], fields for each and
 * buttons to move or remove it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryRow(
  category: DownloadCategory,
  open: Boolean,
  onToggle: () -> Unit,
  onChange: (DownloadCategory) -> Unit,
  onMoveUp: (() -> Unit)?,
  onMoveDown: (() -> Unit)?,
  onRemove: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  // One child of the group, so no divider runs between the header and its fields.
  Column(Modifier.fillMaxWidth().background(colors.surface)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier.fillMaxWidth()
        .clickable(
          role = Role.Button,
          onClickLabel = if (open) {
            stringResource(Res.string.settings_action_hide)
          } else {
            stringResource(Res.string.action_show)
          },
          onClick = onToggle,
        )
        .heightIn(min = KetchTheme.density.iconButtonTarget + spacing.s4)
        .padding(horizontal = spacing.s4, vertical = spacing.s2),
    ) {
      KetchFileTypeChip(
        fileName = category.extensions.firstOrNull()?.let { "category.$it" }.orEmpty(),
        mimeType = category.mimeTypes.firstOrNull(),
        size = KetchFileTypeChipDefaults.ListSize,
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
        Text(
          text = category.folder,
          style = KetchTheme.typography.body,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = categorySummary(category).resolve(),
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      KetchIconImage(
        icon = if (open) KetchIcon.ChevronUp else KetchIcon.ChevronDown,
        size = KetchTheme.density.controlGlyph,
        tint = colors.textTertiary,
      )
    }
    if (!open) return@Column
    Column(
      modifier = Modifier.fillMaxWidth()
        .padding(start = spacing.s4, top = spacing.s2, end = spacing.s4, bottom = spacing.s4),
      verticalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
      RuleField(
        label = stringResource(Res.string.settings_categories_folder),
        hint = stringResource(Res.string.settings_categories_folder_hint),
      ) {
        SettingsTextInput(
          value = category.folder,
          onCommit = { onChange(category.copy(folder = it)) },
          normalize = { normalizeCategoryFolder(it) ?: it.trim() },
          validate = { typed ->
            if (normalizeCategoryFolder(typed) == null) {
              Res.string.settings_categories_folder_invalid.text()
            } else {
              null
            }
          },
        )
      }
      RuleField(label = stringResource(Res.string.settings_categories_extensions)) {
        SettingsTextInput(
          value = ruleText(category.extensions),
          onCommit = { onChange(category.copy(extensions = parseExtensions(it))) },
          normalize = { ruleText(parseExtensions(it)) },
          placeholder = stringResource(Res.string.settings_categories_extensions_placeholder),
          mono = true,
        )
      }
      RuleField(
        label = stringResource(Res.string.settings_categories_types),
        hint = stringResource(Res.string.settings_categories_types_hint),
      ) {
        SettingsTextInput(
          value = ruleText(category.mimeTypes),
          onCommit = { onChange(category.copy(mimeTypes = parseMimeTypes(it))) },
          normalize = { ruleText(parseMimeTypes(it)) },
          validate = { typed ->
            if (parseMimeTypes(typed).all(::isMimeTypeRule)) {
              null
            } else {
              Res.string.settings_categories_types_invalid.text()
            }
          },
          placeholder = stringResource(Res.string.settings_categories_types_placeholder),
          mono = true,
        )
      }
      RuleField(
        label = stringResource(Res.string.settings_categories_sites),
        hint = stringResource(Res.string.settings_categories_sites_hint),
      ) {
        SettingsTextInput(
          value = ruleText(category.hosts),
          onCommit = { onChange(category.copy(hosts = parseHosts(it))) },
          normalize = { ruleText(parseHosts(it)) },
          placeholder = stringResource(Res.string.settings_categories_sites_placeholder),
          mono = true,
        )
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        KetchButton(
          text = stringResource(Res.string.settings_categories_move_up),
          onClick = { onMoveUp?.invoke() },
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.ChevronUp,
          enabled = onMoveUp != null,
        )
        KetchButton(
          text = stringResource(Res.string.settings_categories_move_down),
          onClick = { onMoveDown?.invoke() },
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.ChevronDown,
          enabled = onMoveDown != null,
        )
        KetchButton(
          text = stringResource(Res.string.settings_categories_remove),
          onClick = onRemove,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Trash,
        )
      }
    }
  }
}

/** A field of an open category, under its [label], with a [hint] below. */
@Composable
private fun RuleField(label: String, hint: String? = null, field: @Composable () -> Unit) {
  val colors = KetchTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
    Text(text = label, style = KetchTheme.typography.label, color = colors.textSecondary)
    field()
    if (hint != null) {
      Text(text = hint, style = KetchTheme.typography.caption, color = colors.textTertiary)
    }
  }
}
