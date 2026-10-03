package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KeyChord
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.toKeyPress
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_target_on
import ketch.app.shared.generated.resources.settings_close
import ketch.app.shared.generated.resources.settings_search_placeholder
import org.jetbrains.compose.resources.stringResource

/** The app's log files, which the About page opens or shares; `null` when the app keeps none. */
val LocalFileLogger = staticCompositionLocalOf<FileLogger?> { null }

/**
 * Settings, filling the space it is given: the pages beside the open one, or on narrow windows
 * the list of pages and one page at a time. The app's pages come first, then the pages of one
 * device, picked with the device chip without switching the device the app shows.
 *
 * Every generic way into Settings asks for General without a device; that reopens the page
 * shown last, so only a deep link to another page, or to General on a given device, moves it.
 * On desktop, where Settings is a window of its own, the window frames it and closes it; in
 * the app's shell it brings its own close button. Esc clears a search, then closes, and the
 * primary modifier with F (⌘F) searches.
 *
 * @param target page asked for; `null` reopens the page shown last.
 * @param onClose closes Settings.
 */
@Composable
fun SettingsHost(state: AppState, target: SettingsTarget?, onClose: () -> Unit) {
  SettingsContent(state, target, onClose)
}

/**
 * [SettingsHost], with [initialQuery] already typed in the search field, which then has the
 * keyboard; for tests.
 */
@Composable
internal fun SettingsContent(
  state: AppState,
  target: SettingsTarget?,
  onClose: () -> Unit,
  initialQuery: String = "",
) {
  val appSettings = state.appSettings
  val aiSettings = state.aiSettings
  val fileLogger = LocalFileLogger.current
  val instances by state.instances.collectAsState()
  val active by state.activeInstance.collectAsState()
  val inWindow = LocalDesktopHooks.current.isSupported
  val permission = rememberNotificationPermission()

  var deviceId by rememberSaveable { mutableStateOf(target?.deviceId) }
  var query by rememberSaveable { mutableStateOf(initialQuery) }
  var jump by remember { mutableStateOf<SettingsJumpRequest?>(null) }
  var jumps by remember { mutableIntStateOf(0) }
  var selectedHit by remember { mutableIntStateOf(0) }
  var searchFocusRequests by remember { mutableIntStateOf(if (initialQuery.isEmpty()) 0 else 1) }
  val rootFocus = remember { FocusRequester() }

  // The desktop app saves some sections itself, such as the close action after "Don't ask
  // again", so read them again each time Settings opens.
  LaunchedEffect(Unit) { appSettings.reload() }

  val device = instances.firstOrNull { it.deviceId == deviceId }
    ?: active
    ?: instances.firstOrNull()
  val categories = SettingsCategory.visible(
    discoverSupported = aiSettings.supported,
    device = device,
    serverSupported = state.instanceManager.isLocalServerSupported,
  )
  val controller = device?.let(state::settingsFor)
  LaunchedEffect(controller) {
    controller?.loadDownload()
    controller?.loadNetworks()
  }
  val summaries = rememberSettingsSummaries(state, device)
  val features = buildSet {
    if (inWindow) add(SettingsFeature.Desktop)
    if (isMobilePlatform) {
      add(SettingsFeature.Mobile)
    } else {
      add(SettingsFeature.SetupChecklist)
      add(SettingsFeature.Keyboard)
    }
    if (permission != null) add(SettingsFeature.BrowserNotifications)
    if (fileLogger != null) add(SettingsFeature.Logs)
  }
  val index = rememberSettingsSearchIndex()
  val hits = remember(query, categories, features, index) {
    searchSettings(query, categories, features, index)
  }
  val searching = query.isNotBlank()

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val twoPane = maxWidth >= TwoPaneMinWidth
    var pageName by rememberSaveable {
      val last = pageNamed(appSettings.ui.settingsPage)
      mutableStateOf(pageFor(target, current = null, last = last, list = !twoPane)?.name)
    }
    // Later requests, such as "Speed settings…" while Settings shows, even for the same page.
    LaunchedEffect(target, state.settingsRequests) {
      target?.deviceId?.let { deviceId = it }
      val last = pageNamed(appSettings.ui.settingsPage)
      pageName = pageFor(target, pageNamed(pageName), last, list = !twoPane)?.name
    }
    val page = pageName?.let { name -> categories.firstOrNull { it.page.name == name } }
      ?: pageName?.let { fallbackPage(it, categories) }
    LaunchedEffect(page) {
      val name = page?.page?.name ?: return@LaunchedEffect
      if (name != appSettings.ui.settingsPage) appSettings.saveUi { it.copy(settingsPage = name) }
    }
    val open = { category: SettingsCategory ->
      pageName = category.page.name
      jump = null
    }
    val openHit = { hit: SettingsHit ->
      pageName = hit.entry.category.page.name
      jump = SettingsJumpRequest(hit.entry.anchors, ++jumps)
    }
    val search = @Composable {
      SettingsSearchField(
        query = query,
        onQueryChange = {
          query = it
          selectedHit = 0
        },
        focusRequests = searchFocusRequests,
        showShortcut = twoPane,
        onMove = { step ->
          if (hits.isNotEmpty()) selectedHit = (selectedHit + step).coerceIn(0, hits.lastIndex)
        },
        onSubmit = { hits.getOrNull(selectedHit)?.let(openHit) },
      )
    }
    val results: (@Composable () -> Unit)? = if (searching) {
      {
        SettingsSearchResults(
          query = query,
          hits = hits,
          selected = selectedHit,
          onOpen = openHit,
          inGroup = !twoPane,
          onSurface = !inWindow,
        )
      }
    } else {
      null
    }
    val selectDevice = { entry: InstanceEntry -> deviceId = entry.deviceId }
    val chip: (@Composable () -> Unit)? = device?.let { selected ->
      { SettingsDeviceChip(instances, selected, onSelect = selectDevice) }
    }
    val systemDeviceName = instances.firstOrNull { it is EmbeddedInstance }?.label
    val content: @Composable (SettingsCategory) -> Unit = { category ->
      SettingsCategoryContent(
        category = category,
        state = state,
        device = device,
        systemDeviceName = systemDeviceName,
      )
    }
    val closeOrBack = {
      if (!twoPane && pageName != null) pageName = null else onClose()
    }
    if (!inWindow || !twoPane) {
      NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = closeOrBack,
      )
    }
    val keys = Modifier
      .onPreviewKeyEvent { event ->
        val find = event.type == KeyEventType.KeyDown && event.toKeyPress() == SearchPress
        if (find) {
          if (!twoPane) pageName = null
          searchFocusRequests++
        }
        find
      }
      .onKeyEvent { event ->
        val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
        when {
          !escape -> {}
          // On a phone's page the search field is out of sight, so Esc goes back first.
          searching && (twoPane || pageName == null) -> query = ""
          else -> closeOrBack()
        }
        escape
      }
      // Settings takes the keyboard as it opens, so ⌘F and Esc work before anything is clicked.
      .focusRequester(rootFocus)
      .focusable()
    LaunchedEffect(Unit) {
      if (initialQuery.isEmpty()) rootFocus.requestFocus()
    }

    if (twoPane) {
      val selected = page ?: categories.first()
      Row(
        modifier = keys.fillMaxSize()
          .then(if (inWindow) Modifier else Modifier.background(KetchTheme.colors.surface)),
      ) {
        SettingsNav(
          categories = categories,
          selected = selected,
          summaries = summaries,
          onOpen = open,
          deviceChip = chip,
          search = search,
          results = results,
          onSurface = !inWindow,
          modifier = Modifier.width(KetchTheme.spacing.sidebarWidth),
        )
        val pane = if (inWindow) {
          Modifier.weight(1f).fillMaxHeight()
            .padding(
              top = KetchTheme.spacing.cardInset,
              end = KetchTheme.spacing.cardInset,
              bottom = KetchTheme.spacing.cardInset,
            )
            .ketchSurface(
              level = KetchElevationLevel.E1,
              shape = KetchTheme.shapes.dialog,
              fill = KetchTheme.colors.surface,
              border = KetchTheme.colors.hairline,
            )
        } else {
          Modifier.weight(1f).fillMaxHeight()
        }
        if (!inWindow) {
          Box(
            Modifier.width(HairlineWidth).fillMaxHeight().background(KetchTheme.colors.hairline),
          )
        }
        SettingsCategoryPage(
          category = selected,
          inset = KetchTheme.density.pagePadding,
          onBack = null,
          content = content,
          modifier = pane,
          jump = jump,
          actions = {
            if (selected.page.isDevicePage && device != null) {
              SettingsDeviceChip(
                devices = instances,
                selected = device,
                onSelect = selectDevice,
                label = stringResource(Res.string.device_target_on),
              )
            }
            if (!inWindow) {
              KetchIconButton(
                icon = KetchIcon.Close,
                onClick = onClose,
                contentDescription = stringResource(Res.string.settings_close),
              )
            }
          },
        )
      }
    } else {
      Box(keys.fillMaxSize().background(KetchTheme.colors.canvas)) {
        if (page == null) {
          SettingsList(
            categories = categories,
            summaries = summaries,
            inset = KetchTheme.density.pagePadding,
            onOpen = open,
            onClose = onClose,
            deviceChip = chip,
            search = search,
            results = results,
          )
        } else {
          SettingsCategoryPage(
            category = page,
            inset = KetchTheme.density.pagePadding,
            onBack = { pageName = null },
            content = content,
            jump = jump,
            actions = if (page.page.isDevicePage && device != null) {
              { SettingsDeviceChip(instances, device, onSelect = selectDevice) }
            } else {
              null
            },
          )
        }
      }
    }
  }
}

/**
 * The page Settings shows for [target].
 *
 * A target that names a page other than General, or names a device, opens that page. General
 * without a device is what every generic way into Settings asks for (⌘,, menus, the sidebar), so
 * it keeps the [current] page, else reopens the [last] one, else shows General; on a phone,
 * whose Settings starts at the [list] of pages, it shows that list.
 *
 * @param current page on screen now, or `null`.
 * @param last page shown when Settings was last open, or `null`.
 * @param list whether the list of pages is a page of its own, as on phones.
 * @return the page to show, or `null` for the list of pages.
 */
internal fun pageFor(
  target: SettingsTarget?,
  current: SettingsTarget.Page?,
  last: SettingsTarget.Page?,
  list: Boolean,
): SettingsTarget.Page? {
  val generic = target == null ||
    target.page == SettingsTarget.Page.General && target.deviceId == null
  return when {
    !generic -> target.page
    current != null -> current
    list -> null
    else -> last ?: SettingsTarget.Page.General
  }
}

/** The page called [name], or `null` for none. */
internal fun pageNamed(name: String?): SettingsTarget.Page? =
  name?.let { SettingsTarget.Page.entries.firstOrNull { page -> page.name == it } }

/**
 * The page to show in place of the page named [name], which [categories] no longer offer, such
 * as Sharing after picking a remote device: the device's Downloads page for a device page,
 * otherwise General.
 */
internal fun fallbackPage(name: String, categories: List<SettingsCategory>): SettingsCategory? {
  val page = pageNamed(name) ?: return null
  val preferred = if (page.isDevicePage) SettingsCategory.Downloads else SettingsCategory.General
  return preferred.takeIf { it in categories } ?: categories.firstOrNull()
}

/**
 * The field that searches Settings. ↑ and ↓ move through the results and Enter opens the one
 * picked; Esc is left to Settings, which clears the search.
 *
 * @param focusRequests counts up each time the field should take the keyboard, as on ⌘F.
 * @param showShortcut shows the search chord at the end of the empty field.
 */
@Composable
private fun SettingsSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  focusRequests: Int,
  showShortcut: Boolean,
  onMove: (Int) -> Unit,
  onSubmit: () -> Unit,
) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(focusRequests) {
    if (focusRequests > 0) focus.requestFocus()
  }
  val hint = SearchChord.label(KeyboardPlatform.current)
  KetchTextField(
    value = query,
    onValueChange = onQueryChange,
    placeholder = stringResource(Res.string.settings_search_placeholder),
    leadingIcon = KetchIcon.Search,
    trailing = if (showShortcut && query.isEmpty()) {
      {
        Text(
          text = hint,
          style = KetchTheme.typography.labelS,
          color = KetchTheme.colors.textTertiary,
          modifier = Modifier.padding(end = KetchTheme.spacing.s1),
        )
      }
    } else {
      null
    },
    modifier = Modifier.fillMaxWidth()
      .focusRequester(focus)
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.DirectionDown -> onMove(1)
          Key.DirectionUp -> onMove(-1)
          Key.Enter, Key.NumPadEnter -> onSubmit()
          else -> return@onPreviewKeyEvent false
        }
        true
      },
  )
}

/** ⌘F on Apple keyboards, Ctrl+F elsewhere: searches Settings while it has the keyboard. */
private val SearchChord = KeyChord(Key.F, primary = true)
private val SearchPress = SearchChord.resolve(KeyboardPlatform.current)

/** Narrowest Settings shows the pages beside the open one; narrower ones show one at a time. */
private val TwoPaneMinWidth = 600.dp
