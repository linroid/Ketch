package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchSidebarItem
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.InstanceSettingsController
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.theme.KetchTheme

/** Below this width the categories and their page are shown one at a time. */
private val TWO_PANE_MIN_WIDTH = 760.dp

/**
 * The settings of one [category]. [SettingsPage] and [SettingsDialog]
 * both show categories through this.
 *
 * Changes are applied as they are made, so there is nothing to save.
 *
 * @param appSettings this app's own config sections.
 * @param aiSettings AI discovery settings and provider.
 * @param instanceSettings download and network settings of the active
 *   instance.
 * @param instanceLabel name of the active instance.
 * @param systemDeviceName name this device goes by when none is set, or
 *   `null` when the app has no local instance (the web app).
 * @param serverState whether the local server is running.
 * @param onTestAi call the AI provider with the saved settings.
 * @param onStartServer start the local server.
 * @param onStopServer stop the local server.
 */
@Composable
fun SettingsCategoryContent(
  category: SettingsCategory,
  appSettings: AppSettingsController,
  aiSettings: AiSettingsController,
  instanceSettings: InstanceSettingsController,
  instanceLabel: String,
  systemDeviceName: String?,
  serverState: ServerState,
  onTestAi: () -> Unit,
  onStartServer: () -> Unit,
  onStopServer: () -> Unit,
) {
  when (category) {
    SettingsCategory.General -> GeneralSettings(appSettings, systemDeviceName)
    SettingsCategory.Downloads -> DownloadSettings(instanceSettings, instanceLabel)
    SettingsCategory.Network -> NetworkSettings(instanceSettings, instanceLabel)
    SettingsCategory.RemoteAccess -> RemoteAccessSettings(
      config = appSettings.config.server,
      serverState = serverState,
      onChange = { appSettings.saveServer(it) },
      onStart = onStartServer,
      onStop = onStopServer,
    )
    SettingsCategory.Ai -> AiDiscoverySettings(
      settings = aiSettings.settings,
      supported = aiSettings.supported,
      resolveCredentials = aiSettings::withPlatformCredentials,
      connectionTest = aiSettings.connectionTest,
      onChange = { aiSettings.save(it) },
      onTest = onTestAi,
    )
    SettingsCategory.About -> AboutSettings()
  }
}

/**
 * Settings as a full page, for windows too narrow for [SettingsDialog].
 * Medium widths show the categories beside the selected page; phones
 * show the list first and open a category on tap.
 *
 * @param categories categories to list, in order.
 * @param content renders one category; see [SettingsCategoryContent].
 * @param onClose leaves Settings when back is pressed on the category
 *   list; `null` leaves back handling to the platform.
 * @param initialCategory category to open first; on phones `null` starts
 *   at the category list.
 */
@Composable
fun SettingsPage(
  categories: List<SettingsCategory>,
  content: @Composable (SettingsCategory) -> Unit,
  onClose: (() -> Unit)? = null,
  initialCategory: SettingsCategory? = null,
) {
  var openName by rememberSaveable { mutableStateOf(initialCategory?.name) }
  val open = categories.firstOrNull { it.name == openName }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val inset = if (maxWidth < 600.dp) 16.dp else 32.dp
    val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
    // Back steps out of a category first, then out of Settings.
    val onBack = if (open != null && !twoPane) {
      { openName = null }
    } else {
      onClose
    }
    if (onBack != null) {
      NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = onBack,
      )
    }
    when {
      twoPane -> Row(Modifier.fillMaxSize()) {
        val selected = open ?: categories.first()
        SettingsCategoryNav(
          categories = categories,
          selected = selected,
          onSelect = { openName = it.name },
        )
        VerticalDivider(color = KetchTheme.colors.outlineVariant)
        SettingsCategoryPage(
          category = selected,
          inset = inset,
          onBack = null,
          content = content,
          modifier = Modifier.weight(1f),
        )
      }
      open == null -> CategoryList(
        categories = categories,
        inset = inset,
        onOpen = { openName = it.name },
      )
      else -> SettingsCategoryPage(
        category = open,
        inset = inset,
        onBack = { openName = null },
        content = content,
      )
    }
  }
}

/**
 * Category list beside a settings page.
 *
 * @param background fill behind the list, e.g. to set it apart in a
 *   dialog.
 * @param footer optional content pinned under the list.
 */
@Composable
internal fun SettingsCategoryNav(
  categories: List<SettingsCategory>,
  selected: SettingsCategory,
  onSelect: (SettingsCategory) -> Unit,
  background: Color = Color.Transparent,
  footer: (@Composable () -> Unit)? = null,
) {
  Column(
    modifier = Modifier.width(232.dp)
      .fillMaxHeight()
      .background(background)
      .padding(vertical = 24.dp),
  ) {
    Text(
      text = "Settings",
      style = KetchTheme.typography.displaySmall,
      color = KetchTheme.colors.onBackground,
      modifier = Modifier.padding(start = 22.dp, end = 16.dp, bottom = 16.dp),
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      categories.forEach { category ->
        KetchSidebarItem(
          label = category.title,
          icon = category.icon,
          selected = category == selected,
          onClick = { onSelect(category) },
        )
      }
    }
    footer?.invoke()
  }
}

@Composable
private fun CategoryList(
  categories: List<SettingsCategory>,
  inset: Dp,
  onOpen: (SettingsCategory) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(inset),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Text(
      text = "Settings",
      style = KetchTheme.typography.displaySmall,
      color = KetchTheme.colors.onBackground,
    )
    SettingsGroup {
      categories.forEach { category ->
        SettingsRow(
          title = category.title,
          description = category.summary,
          modifier = Modifier.clickable(role = Role.Button) { onOpen(category) },
          leading = { CategoryIcon(category.icon) },
          trailing = {
            KetchIconImage(
              icon = KetchIcon.Chevron,
              size = 14.dp,
              tint = KetchTheme.colors.onSurfaceDim,
            )
          },
        )
      }
    }
  }
}

@Composable
private fun CategoryIcon(icon: KetchIcon) {
  val colors = KetchTheme.colors
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier.size(32.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(colors.primaryContainer),
  ) {
    KetchIconImage(icon = icon, size = 17.dp, tint = colors.primary)
  }
}

/**
 * One category's page, centred and width-capped so rows stay readable
 * on wide windows.
 *
 * @param onBack returns to the category list; `null` when the list is
 *   already on screen.
 */
@Composable
internal fun SettingsCategoryPage(
  category: SettingsCategory,
  inset: Dp,
  onBack: (() -> Unit)?,
  content: @Composable (SettingsCategory) -> Unit,
  modifier: Modifier = Modifier,
) {
  // A fresh scroll position for every category.
  key(category) {
    Column(
      modifier = modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = inset, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (onBack != null) {
            KetchIconButton(
              icon = KetchIcon.ChevronLeft,
              contentDescription = "Back to settings",
              onClick = onBack,
            )
          }
          SettingsHeader(title = category.title, description = category.summary)
        }
        content(category)
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}
