package com.linroid.ketch.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchHueTile
import com.linroid.ketch.app.components.KetchHueTileDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.instance.detail
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.platform.IntegrationStatus
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.LocalIntegrationStatus
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SettingsSection
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.clipboardMode
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.folderNameText
import com.linroid.ketch.app.state.isDocumentTree
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.app.state.toDeviceHealth
import com.linroid.ketch.app.theme.FileTypeHue
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.pairingAddresses
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.ClipboardMode
import com.linroid.ketch.config.NotificationMode
import com.linroid.ketch.config.NotificationSettings
import com.linroid.ketch.config.SearchProvider
import com.linroid.ketch.config.SpeedSettings
import com.linroid.ketch.config.ThemeMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_target_choose
import ketch.app.shared.generated.resources.device_target_connecting
import ketch.app.shared.generated.resources.device_target_offline
import ketch.app.shared.generated.resources.settings_back
import ketch.app.shared.generated.resources.settings_changes_apply
import ketch.app.shared.generated.resources.settings_close
import ketch.app.shared.generated.resources.settings_device_menu_title
import ketch.app.shared.generated.resources.settings_device_unauthorized
import ketch.app.shared.generated.resources.settings_downloads_folder
import ketch.app.shared.generated.resources.settings_network_system_default
import ketch.app.shared.generated.resources.settings_search_count
import ketch.app.shared.generated.resources.settings_search_no_match
import ketch.app.shared.generated.resources.settings_speed_mode_full
import ketch.app.shared.generated.resources.settings_summary_clipboard_fill
import ketch.app.shared.generated.resources.settings_summary_clipboard_off
import ketch.app.shared.generated.resources.settings_summary_clipboard_suggest
import ketch.app.shared.generated.resources.settings_summary_discover_search
import ketch.app.shared.generated.resources.settings_summary_downloads_all
import ketch.app.shared.generated.resources.settings_summary_downloads_count
import ketch.app.shared.generated.resources.settings_summary_extension
import ketch.app.shared.generated.resources.settings_summary_extension_in
import ketch.app.shared.generated.resources.settings_summary_extension_in_more
import ketch.app.shared.generated.resources.settings_summary_extension_missing
import ketch.app.shared.generated.resources.settings_summary_limit
import ketch.app.shared.generated.resources.settings_summary_no_limit
import ketch.app.shared.generated.resources.settings_summary_not_set_up
import ketch.app.shared.generated.resources.settings_summary_notifications_on
import ketch.app.shared.generated.resources.settings_summary_off
import ketch.app.shared.generated.resources.settings_summary_sharing_local
import ketch.app.shared.generated.resources.settings_summary_sharing_on
import ketch.app.shared.generated.resources.settings_summary_sharing_port
import ketch.app.shared.generated.resources.settings_summary_speed_auto_full
import ketch.app.shared.generated.resources.settings_summary_speed_auto_slow_lane
import ketch.app.shared.generated.resources.settings_summary_speed_capped
import ketch.app.shared.generated.resources.settings_summary_speed_slow_lane
import ketch.app.shared.generated.resources.settings_summary_trackers
import ketch.app.shared.generated.resources.settings_summary_version
import ketch.app.shared.generated.resources.settings_theme_dark
import ketch.app.shared.generated.resources.settings_theme_light
import ketch.app.shared.generated.resources.settings_theme_system
import ketch.app.shared.generated.resources.settings_title
import ketch.app.shared.generated.resources.settings_torrent_no_trackers
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The settings of one [category]. Every layout of Settings shows its pages through this.
 *
 * Changes are applied as they are made, so there is nothing to save.
 *
 * @param state the app, whose device pages edit [device].
 * @param device device whose download, speed, network, torrent and sharing settings are shown;
 *   `null` while none is connected.
 * @param systemDeviceName name this device goes by when none is set, or `null` when the app has
 *   no device of its own (the web app).
 */
@Composable
fun SettingsCategoryContent(
  category: SettingsCategory,
  state: AppState,
  device: InstanceEntry?,
  systemDeviceName: String?,
) {
  when (category) {
    SettingsCategory.General -> GeneralSettings(state, systemDeviceName)
    SettingsCategory.Notifications -> NotificationSettingsPage(state)
    SettingsCategory.Integration -> IntegrationSettingsPage(state)
    SettingsCategory.Discover -> AiDiscoverySettings(state)
    SettingsCategory.About -> AboutSettings(state)
    SettingsCategory.Downloads -> device?.let { DownloadSettings(state, it) } ?: NoDeviceNotice()
    SettingsCategory.Speed -> device?.let { SpeedSettingsPage(state, it) } ?: NoDeviceNotice()
    SettingsCategory.Network -> device?.let { NetworkSettings(state, it) } ?: NoDeviceNotice()
    SettingsCategory.BitTorrent -> device?.let { BitTorrentSettings(state, it) } ?: NoDeviceNotice()
    SettingsCategory.Sharing -> device?.let { SharingSettings(state, it) } ?: NoDeviceNotice()
  }
}

/**
 * One page of Settings: its title, with [actions] such as the device chip at the end, over its
 * scrolling content, which ends with a note that changes apply as they are made.
 *
 * @param inset space at the sides of the page.
 * @param onBack returns to the list of pages; `null` when the list is on screen beside it.
 * @param jump scrolls to a row and flashes it, as a search result asks.
 * @param actions controls at the end of the title line.
 */
@Composable
internal fun SettingsCategoryPage(
  category: SettingsCategory,
  inset: Dp,
  onBack: (() -> Unit)?,
  content: @Composable (SettingsCategory) -> Unit,
  modifier: Modifier = Modifier,
  jump: SettingsJumpRequest? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  // A fresh scroll position for every page.
  key(category) {
    val scroll = rememberScrollState()
    val highlight = colors.accent.copy(alpha = HIGHLIGHT_ALPHA)
    val cardShape = KetchTheme.shapes.card
    val settingsJump = remember(highlight, cardShape) { SettingsJump(highlight, cardShape) }
    val scrolled = remember { CoordinatesHolder() }
    Column(modifier.fillMaxSize()) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        modifier = Modifier.fillMaxWidth()
          .heightIn(min = KetchTheme.density.iconButtonTarget)
          .padding(
            start = if (onBack != null) inset - spacing.s2 else inset,
            end = inset,
            top = spacing.s4,
            bottom = spacing.s3,
          ),
      ) {
        if (onBack != null) {
          KetchIconButton(
            icon = KetchIcon.ChevronLeft,
            onClick = onBack,
            contentDescription = stringResource(Res.string.settings_back),
          )
        }
        Text(
          text = category.titleText.resolve(),
          style = KetchTheme.typography.titleL,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        actions?.invoke(this)
      }
      CompositionLocalProvider(LocalSettingsJump provides settingsJump) {
        Column(
          modifier = Modifier.weight(1f)
            .fillMaxWidth()
            .verticalScroll(scroll)
            .onGloballyPositioned { scrolled.value = it }
            .padding(start = inset, end = inset, top = spacing.s1, bottom = spacing.s4),
        ) {
          Column(
            modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sectionGap),
          ) {
            content(category)
            Text(
              text = stringResource(Res.string.settings_changes_apply),
              style = KetchTheme.typography.caption,
              color = colors.textTertiary,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    }
    val margin = with(LocalDensity.current) { spacing.s4.toPx() }
    val animate = !KetchTheme.reduceMotion
    // Runs the request once the page is laid out; see SettingsJump.jump.
    LaunchedEffect(settingsJump, jump) {
      if (jump != null) settingsJump.jump(jump.anchors, scroll, { scrolled.value }, margin, animate)
    }
  }
}

private class CoordinatesHolder {
  var value: LayoutCoordinates? = null
}

/**
 * The column of pages beside the open one, in two groups, with search at the top. While a query
 * is typed it lists what matches instead. Each page is one line, as in the app's sidebar.
 *
 * @param summaries the live one-line state of each page, which screen readers announce as the
 *   state of its item.
 * @param deviceChip the chip that picks the device the device pages edit; `null` hides it.
 * @param search the search field.
 * @param results the search results, shown in place of the pages while a query is typed.
 * @param onSurface whether the column sits on a surface rather than on the canvas, which decides
 *   how the open page is marked.
 */
@Composable
internal fun SettingsNav(
  categories: List<SettingsCategory>,
  selected: SettingsCategory,
  summaries: Map<SettingsCategory, UiText>,
  onOpen: (SettingsCategory) -> Unit,
  deviceChip: (@Composable () -> Unit)?,
  search: @Composable () -> Unit,
  results: (@Composable () -> Unit)?,
  onSurface: Boolean,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  Column(modifier.fillMaxHeight()) {
    Box(Modifier.padding(start = spacing.s3, end = spacing.s3, top = spacing.s3)) { search() }
    Column(
      modifier = Modifier.weight(1f)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = spacing.s1, vertical = spacing.s3)
        .selectableGroup(),
    ) {
      if (results != null) {
        results()
        return@Column
      }
      for (section in SettingsSection.entries) {
        val pages = categories.filter { it.section == section }
        if (pages.isEmpty()) continue
        SectionHeader(
          title = section.title.resolve(),
          trailing = deviceChip.takeIf { section == SettingsSection.Device },
          modifier = Modifier.padding(
            top = if (section == SettingsSection.App) 0.dp else spacing.s4,
          ),
        )
        pages.forEach { category ->
          SettingsNavItem(
            category = category,
            summary = summaries[category]?.resolve(),
            selected = category == selected,
            onSurface = onSurface,
            onClick = { onOpen(category) },
          )
        }
      }
    }
  }
}

/** The eyebrow over a group of pages, with an optional control at its end. */
@Composable
private fun SectionHeader(
  title: String,
  trailing: (@Composable () -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier.fillMaxWidth()
      .heightIn(min = KetchTheme.density.chip + spacing.s1)
      .padding(start = spacing.s3, end = spacing.s2, bottom = spacing.s1),
  ) {
    KetchEyebrow(title, color = KetchTheme.colors.textSecondary, maxLines = 1)
    if (trailing != null) {
      Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
  }
}

/**
 * One page in [SettingsNav], as tall as a sidebar item: its hue tile and its name. Screen
 * readers announce its [summary] as its state.
 */
@Composable
private fun SettingsNavItem(
  category: SettingsCategory,
  summary: String?,
  selected: Boolean,
  onSurface: Boolean,
  onClick: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sidebarItem
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val fill by animateColorAsState(
    targetValue = itemFill(selected, hovered, onSurface),
    animationSpec = tween(KetchTheme.motion.micro),
  )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.iconLabelGap),
    modifier = Modifier.fillMaxWidth()
      .padding(horizontal = spacing.s2, vertical = spacing.s0_5)
      .focusRing(focus.visible, shape, colors.focusRing)
      .heightIn(min = KetchTheme.density.sidebarItem)
      .background(fill, shape)
      .trackFocusVisibility(focus)
      .selectable(
        selected = selected,
        interactionSource = interactions,
        indication = null,
        role = Role.Tab,
        onClick = onClick,
      )
      .semantics { if (!summary.isNullOrEmpty()) stateDescription = summary }
      .padding(horizontal = spacing.s1),
  ) {
    KetchHueTile(icon = category.icon, hue = category.hue, size = KetchHueTileDefaults.XSmall)
    Text(
      text = category.titleText.resolve(),
      style = KetchTheme.typography.label,
      fontWeight = if (selected) FontWeight.SemiBold else null,
      color = if (selected) colors.textPrimary else colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * Fill of a picked or hovered item in Settings' lists. The sidebar's translucent pills only show
 * on the canvas, so on a [surface][onSurface] the surface's own hover and pressed tones mark them.
 */
@Composable
private fun itemFill(selected: Boolean, hovered: Boolean, onSurface: Boolean): Color {
  val colors = KetchTheme.colors
  return when {
    selected -> if (onSurface) colors.surfacePressed else colors.sidebarItemSelected
    hovered -> if (onSurface) colors.surfaceHover else colors.sidebarItemHover
    // Fades by alpha alone: Color.Transparent is transparent black, which flashes grey.
    else -> (if (onSurface) colors.surfaceHover else colors.sidebarItemHover).copy(alpha = 0f)
  }
}

/**
 * The phone's Settings: the pages in two groups, each with its summary, and search above them.
 *
 * @param onClose leaves Settings; `null` leaves that to the platform's back.
 */
@Composable
internal fun SettingsList(
  categories: List<SettingsCategory>,
  summaries: Map<SettingsCategory, UiText>,
  inset: Dp,
  onOpen: (SettingsCategory) -> Unit,
  onClose: (() -> Unit)?,
  deviceChip: (@Composable () -> Unit)?,
  search: @Composable () -> Unit,
  results: (@Composable () -> Unit)?,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    modifier = Modifier.fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = inset, vertical = spacing.s4),
    verticalArrangement = Arrangement.spacedBy(spacing.s4),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      // The back button lines up with the one on each page, its glyph at the page's edge.
      modifier = if (onClose != null) {
        Modifier.offset(x = -spacing.s2)
      } else {
        Modifier.padding(start = spacing.s1)
      },
    ) {
      if (onClose != null) {
        KetchIconButton(
          icon = KetchIcon.ChevronLeft,
          onClick = onClose,
          contentDescription = stringResource(Res.string.settings_close),
        )
      }
      Text(
        text = stringResource(Res.string.settings_title),
        style = KetchTheme.typography.pageTitle,
        color = colors.textPrimary,
      )
    }
    search()
    if (results != null) {
      results()
      return@Column
    }
    for (section in SettingsSection.entries) {
      val pages = categories.filter { it.section == section }
      if (pages.isEmpty()) continue
      SettingsGroup(
        title = section.title.resolve(),
        action = deviceChip.takeIf { section == SettingsSection.Device },
      ) {
        pages.forEach { category ->
          SettingsRow(
            title = category.titleText.resolve(),
            description = (summaries[category] ?: category.descriptionText).resolve(),
            modifier = Modifier.clickable(role = Role.Button) { onOpen(category) },
            leading = {
              KetchHueTile(
                icon = category.icon,
                hue = category.hue,
                size = KetchHueTileDefaults.Small,
              )
            },
            trailing = { Chevron() },
          )
        }
      }
    }
    Text(
      text = stringResource(Res.string.settings_changes_apply),
      style = KetchTheme.typography.caption,
      color = colors.textTertiary,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Search results: each setting with the matching words highlighted and the page it is on. The
 * [selected] one is what Enter opens.
 *
 * @param inGroup draws the results as the rows of a group, as on phones, rather than as
 *   navigation items.
 * @param onSurface whether navigation items sit on a surface rather than on the canvas.
 */
@Composable
internal fun SettingsSearchResults(
  query: String,
  hits: List<SettingsHit>,
  selected: Int,
  onOpen: (SettingsHit) -> Unit,
  inGroup: Boolean,
  onSurface: Boolean,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  if (hits.isEmpty()) {
    Text(
      text = stringResource(Res.string.settings_search_no_match, query.trim()),
      style = KetchTheme.typography.bodyS,
      color = colors.textSecondary,
      modifier = Modifier.padding(horizontal = spacing.s3, vertical = spacing.s2),
    )
    return
  }
  val match = SpanStyle(
    color = colors.accentText,
    fontWeight = FontWeight.SemiBold,
    background = colors.accentSoft,
  )
  val items = @Composable {
    hits.forEachIndexed { index, hit ->
      SearchResult(
        hit = hit,
        match = match,
        selected = index == selected,
        onClick = { onOpen(hit) },
        filled = inGroup,
        onSurface = inGroup || onSurface,
      )
    }
  }
  if (inGroup) {
    val count = pluralStringResource(Res.plurals.settings_search_count, hits.size, hits.size)
    SettingsGroup(title = count) { items() }
  } else {
    items()
  }
}

@Composable
private fun SearchResult(
  hit: SettingsHit,
  match: SpanStyle,
  selected: Boolean,
  onClick: () -> Unit,
  filled: Boolean,
  onSurface: Boolean,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val entry = hit.entry
  val shape = KetchTheme.shapes.sidebarItem
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val fill = itemFill(selected, hovered, onSurface)
  val outer = if (filled) {
    Modifier.fillMaxWidth().background(colors.surface)
  } else {
    Modifier.fillMaxWidth().padding(horizontal = spacing.s2, vertical = spacing.s0_5)
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.iconLabelGap),
    modifier = outer
      .focusRing(focus.visible, shape, colors.focusRing)
      .background(fill, if (filled) RectangleShape else shape)
      .ketchClickable(interactions, focus, onClick = onClick)
      .padding(
        horizontal = if (filled) spacing.s4 else spacing.s1,
        vertical = if (filled) spacing.s3 else spacing.s1,
      ),
  ) {
    // Beside the open page the results line up with the list of pages they replace.
    KetchHueTile(
      icon = entry.category.icon,
      hue = entry.category.hue,
      size = if (filled) KetchHueTileDefaults.Small else KetchHueTileDefaults.XSmall,
    )
    Column(Modifier.weight(1f)) {
      Text(
        text = highlighted(entry.title, hit.titleMatches, match),
        style = KetchTheme.typography.label,
        color = colors.textPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      // A page answers by its description, so that is where its matches show.
      val where = if (entry.title == entry.page) {
        highlighted(entry.description, hit.descriptionMatches, match)
      } else {
        AnnotatedString(entry.page)
      }
      if (where.isNotEmpty()) {
        Text(
          text = where,
          style = KetchTheme.typography.caption,
          color = colors.textTertiary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * The pill naming the device the device pages edit, such as "(LM) This Mac ▾"; with two
 * devices or more it opens a menu of them. Devices that are not connected are listed but cannot
 * be picked.
 *
 * @param label word before the device, such as "On"; `null` for none.
 */
@Composable
internal fun SettingsDeviceChip(
  devices: List<InstanceEntry>,
  selected: InstanceEntry,
  onSelect: (InstanceEntry) -> Unit,
  modifier: Modifier = Modifier,
  label: String? = null,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val choosable = devices.size > 1
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, choosable)
  val focus = rememberFocusVisibility()
  var expanded by remember { mutableStateOf(false) }
  val health = devices.associate { key(it.deviceId) { it.deviceId to rememberHealth(it) } }
  Box(modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = Modifier
        .focusRing(focus.visible, shape, colors.focusRing)
        .height(KetchTheme.density.chip)
        .clip(shape)
        .background(colors.surface)
        .background(overlay)
        .border(HairlineWidth, colors.borderStrong, shape)
        .ketchClickable(
          interactions = interactions,
          focus = focus,
          enabled = choosable,
          role = Role.DropdownList,
          onClickLabel = stringResource(Res.string.device_target_choose),
          onClick = { expanded = true },
        )
        .padding(horizontal = spacing.s2),
    ) {
      if (label != null) {
        Text(
          text = label,
          style = KetchTheme.typography.labelS,
          color = colors.textTertiary,
          maxLines = 1,
        )
      }
      DevicePennant(
        deviceId = selected.deviceId,
        name = selected.label,
        size = DevicePennantDefaults.XSmall,
      )
      Text(
        text = selected.displayName.resolve(),
        style = KetchTheme.typography.labelS,
        fontWeight = FontWeight.SemiBold,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false).widthIn(max = ChipNameMaxWidth),
      )
      if (choosable) {
        KetchIconImage(
          icon = KetchIcon.ChevronDown,
          size = KetchTheme.density.controlGlyph,
          tint = colors.textSecondary,
        )
      }
    }
    KetchMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      title = stringResource(Res.string.settings_device_menu_title),
    ) {
      for (device in devices) {
        val state = health[device.deviceId] ?: DeviceHealth.Local()
        item(
          label = device.displayName,
          onClick = { onSelect(device) },
          caption = when (state) {
            DeviceHealth.Connecting -> Res.string.device_target_connecting.text()
            is DeviceHealth.Offline -> Res.string.device_target_offline.text()
            DeviceHealth.Unauthorized -> Res.string.settings_device_unauthorized.text()
            is DeviceHealth.Local, DeviceHealth.Live -> verbatim(device.detail)
          },
          enabled = state.isOnline,
          checked = device.deviceId == selected.deviceId,
        )
      }
    }
  }
}

@Composable
private fun rememberHealth(device: InstanceEntry): DeviceHealth = when (device) {
  is RemoteInstance -> device.connectionState.collectAsState().value.toDeviceHealth()
  else -> DeviceHealth.Local()
}

/** Hue of a page's tile. */
internal val SettingsCategory.hue: FileTypeHue
  get() = when (this) {
    SettingsCategory.General, SettingsCategory.About -> FileTypeHue.Slate
    SettingsCategory.Notifications -> FileTypeHue.Amber
    SettingsCategory.Integration -> FileTypeHue.Indigo
    SettingsCategory.Discover -> FileTypeHue.Violet
    SettingsCategory.Downloads -> FileTypeHue.Blue
    SettingsCategory.Speed -> FileTypeHue.Orange
    SettingsCategory.Network -> FileTypeHue.Teal
    SettingsCategory.BitTorrent -> FileTypeHue.Jade
    SettingsCategory.Sharing -> FileTypeHue.Sky
  }

/**
 * The live one-line state of each page, as the list of pages shows it, such as "Light · Signal"
 * for General or "~/Downloads · 3 at a time" for Downloads. Pages whose state is still loading
 * have none.
 *
 * @param device device the device pages edit.
 */
@Composable
internal fun rememberSettingsSummaries(
  state: AppState,
  device: InstanceEntry?,
): Map<SettingsCategory, UiText> {
  val appSettings = state.appSettings
  val aiSettings = state.aiSettings
  val integration = LocalIntegrationStatus.current
  val desktop = LocalDesktopHooks.current.isSupported
  val serverState by state.serverState.collectAsState()
  val controller = device?.let(state::settingsFor)
  val speedMode = state.speedMode.takeIf { device is EmbeddedInstance }
  val speedSettings = speedMode?.settings?.collectAsState()?.value
  val mode = speedMode?.mode?.collectAsState()?.value
  val download = controller?.download
  val networks = controller?.networks
  return buildMap {
    put(SettingsCategory.General, generalSummary(appSettings.themeMode, appSettings.accent))
    put(SettingsCategory.Notifications, notificationsSummary(appSettings.config.notifications))
    put(
      SettingsCategory.Integration,
      integrationSummary(integration, desktop, appSettings.clipboardMode),
    )
    put(
      SettingsCategory.Discover,
      discoverSummary(aiSettings.settings, aiSettings.withPlatformCredentials(aiSettings.settings)),
    )
    put(SettingsCategory.About, Res.string.settings_summary_version.text(KetchApi.VERSION))
    if (download != null) put(SettingsCategory.Downloads, downloadsSummary(download))
    val speed = when {
      speedMode != null && speedSettings != null && mode != null ->
        speedSummary(mode, speedSettings, speedSettings.slowLane ?: speedMode.suggestedSlowLane)
      download != null -> remoteSpeedSummary(download.speedLimit)
      else -> null
    }
    speed?.let { put(SettingsCategory.Speed, it) }
    networkSummary(networks)?.let { put(SettingsCategory.Network, it) }
    controller?.torrent?.let { put(SettingsCategory.BitTorrent, trackersSummary(it.trackers.size)) }
    put(SettingsCategory.Sharing, sharingSummary(serverState, networks?.available))
  }
}

/** "Light · Signal": the theme and the accent. */
internal fun generalSummary(theme: ThemeMode, accent: KetchAccent): UiText =
  listOf(theme.label, accent.displayName).joinText()

/**
 * "4 on": how many kinds of event are reported at all, or "Off" when none is. "All downloads
 * finished" is reported the way finished downloads are, so it counts only while they are.
 */
internal fun notificationsSummary(settings: NotificationSettings): UiText {
  val finished = settings.finished != NotificationMode.Off
  val on = listOf(
    finished,
    settings.failed != NotificationMode.Off,
    finished && settings.queueDrained,
    settings.deviceOffline,
  ).count { it }
  return if (on == 0) {
    Res.string.settings_summary_off.text()
  } else {
    Res.plurals.settings_summary_notifications_on.text(on)
  }
}

/**
 * "Chrome ✓" when the browser extension connected from Chrome; on desktop without it "Extension
 * not set up"; elsewhere what happens to copied links.
 */
internal fun integrationSummary(
  status: IntegrationStatus,
  desktop: Boolean,
  clipboard: ClipboardMode,
): UiText {
  val connected = status.browsers.filter { it.extensionConnected }.map { it.name }
  return when {
    connected.size == 1 -> Res.string.settings_summary_extension_in.text(connected.single())
    connected.size > 1 -> Res.string.settings_summary_extension_in_more.text(
      connected.first(),
      connected.size - 1,
    )
    status.extensionConnected -> Res.string.settings_summary_extension.text()
    desktop -> Res.string.settings_summary_extension_missing.text()
    else -> clipboard.summary
  }
}

/**
 * "Anthropic · Brave search" once discovery is on and set up; "Off" or "Not set up" otherwise.
 *
 * @param effective [settings] with the credentials the platform supplies.
 */
internal fun discoverSummary(settings: AiSettings, effective: AiSettings): UiText = when {
  !effective.llm.isComplete || !effective.search.isComplete ->
    Res.string.settings_summary_not_set_up.text()
  !settings.enabled -> Res.string.settings_summary_off.text()
  effective.search.provider == SearchProvider.None -> effective.llm.provider.displayName
  else -> Res.string.settings_summary_discover_search.text(
    effective.llm.provider.displayName,
    effective.search.provider.displayName,
  )
}

/**
 * "~/Downloads · 3 at a time": where downloads go and how many run together, "all at once"
 * without a limit.
 */
internal fun downloadsSummary(config: DownloadConfig): UiText {
  val folder = config.defaultDirectory?.let(::shortFolder)
    ?: Res.string.settings_downloads_folder.text()
  val count = config.maxConcurrentDownloads
  return if (count == 0) {
    Res.string.settings_summary_downloads_all.text(folder)
  } else {
    Res.plurals.settings_summary_downloads_count.text(count, folder, count)
  }
}

/**
 * [path] for a one-line summary: "~/Downloads" for a folder right in the home folder on macOS or
 * Linux, otherwise its name.
 */
internal fun shortFolder(path: String): UiText {
  if (isDocumentTree(path)) return folderNameText(path)
  val home = HomeFolder.find(path)
  if (home != null) {
    val rest = path.substring(home.value.length).trim('/')
    if (rest.isNotEmpty() && '/' !in rest) return verbatim("~/$rest")
  }
  return folderNameText(path)
}

/** "Slow lane · 1 MB/s": the mode of a device with speed modes, and the limit it applies. */
internal fun speedSummary(mode: SpeedMode, settings: SpeedSettings, slowLane: SpeedLimit): UiText =
  when (mode) {
    SpeedMode.Full -> if (settings.standard.isUnlimited) {
      Res.string.settings_speed_mode_full.text()
    } else {
      Res.string.settings_summary_speed_capped.text(speedLimitText(settings.standard))
    }
    SpeedMode.SlowLane -> Res.string.settings_summary_speed_slow_lane.text(speedLimitText(slowLane))
    is SpeedMode.Auto -> if (mode.slowLane) {
      Res.string.settings_summary_speed_auto_slow_lane.text(speedLimitText(slowLane))
    } else {
      Res.string.settings_summary_speed_auto_full.text()
    }
  }

/** "Limit 5 MB/s" for a device without speed modes, or "No limit". */
internal fun remoteSpeedSummary(limit: SpeedLimit): UiText = if (limit.isUnlimited) {
  Res.string.settings_summary_no_limit.text()
} else {
  Res.string.settings_summary_limit.text(speedLimitText(limit))
}

/** "en0 + en7": the networks downloads are spread across, or the system's default. */
internal fun networkSummary(networks: NetworkInterfaces?): UiText? {
  if (networks == null) return null
  val selected = networks.config.interfaceIds
  if (!networks.supported || selected.isEmpty()) {
    return Res.string.settings_network_system_default.text()
  }
  val names = networks.available.associate { it.id to it.name }
  return verbatim(selected.joinToString(" + ") { names[it] ?: it })
}

/** "3 trackers", or "No extra trackers". */
internal fun trackersSummary(count: Int): UiText = if (count == 0) {
  Res.string.settings_torrent_no_trackers.text()
} else {
  Res.plurals.settings_summary_trackers.text(count)
}

/**
 * "On · 192.168.1.20:8642" while other devices can connect, "This device only" while only apps
 * here can, and "Off".
 *
 * @param interfaces the device's networks, for its address; `null` while they load.
 */
internal fun sharingSummary(
  server: ServerState,
  interfaces: List<NetworkInterfaceInfo>?,
): UiText = when {
  server !is ServerState.Running -> Res.string.settings_summary_off.text()
  server.config.isLoopbackOnly -> Res.string.settings_summary_sharing_local.text()
  else -> {
    val address = interfaces?.let(::pairingAddresses)?.firstOrNull()
    if (address != null) {
      Res.string.settings_summary_sharing_on.text("$address:${server.port}")
    } else {
      Res.string.settings_summary_sharing_port.text(server.port)
    }
  }
}

/** What happens to copied links, as the clipboard setting reads in summaries. */
internal val ClipboardMode.summary: UiText
  get() = when (this) {
    ClipboardMode.Fill -> Res.string.settings_summary_clipboard_fill
    ClipboardMode.Suggest -> Res.string.settings_summary_clipboard_suggest
    ClipboardMode.Off -> Res.string.settings_summary_clipboard_off
  }.text()

/** How the theme reads in Settings. */
internal val ThemeMode.label: UiText
  get() = when (this) {
    ThemeMode.System -> Res.string.settings_theme_system
    ThemeMode.Light -> Res.string.settings_theme_light
    ThemeMode.Dark -> Res.string.settings_theme_dark
  }.text()

/** Home folders of macOS and Linux, which summaries shorten to "~". */
private val HomeFolder = Regex("^/(Users|home)/[^/]+")

/** Widest the main pane of Settings lets its rows grow. */
private val PageMaxWidth = 640.dp

/** Widest a device name grows in the device chip before it is cut short. */
private val ChipNameMaxWidth = 160.dp

private const val HIGHLIGHT_ALPHA = 0.16f
