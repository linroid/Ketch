package com.linroid.ketch.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchHueTile
import com.linroid.ketch.app.components.KetchHueTileDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.SailLanesIllustration
import com.linroid.ketch.app.components.SailLanesIllustrationDefaults
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.platform.LocalDeviceKind
import com.linroid.ketch.app.platform.localDeviceKind
import com.linroid.ketch.app.platform.rememberFilePicker
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.folderNameText
import com.linroid.ketch.app.theme.FileTypeHue
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.shell.canvasWash
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_back
import ketch.app.shared.generated.resources.action_continue
import ketch.app.shared.generated.resources.action_skip
import ketch.app.shared.generated.resources.onboarding_files_body
import ketch.app.shared.generated.resources.onboarding_files_path
import ketch.app.shared.generated.resources.onboarding_files_title
import ketch.app.shared.generated.resources.onboarding_folder_body
import ketch.app.shared.generated.resources.onboarding_folder_choose_another
import ketch.app.shared.generated.resources.onboarding_folder_not_now
import ketch.app.shared.generated.resources.onboarding_folder_saving_to
import ketch.app.shared.generated.resources.onboarding_folder_title
import ketch.app.shared.generated.resources.onboarding_folder_torrents
import ketch.app.shared.generated.resources.onboarding_folder_use_download
import ketch.app.shared.generated.resources.onboarding_intake_body
import ketch.app.shared.generated.resources.onboarding_intake_copy
import ketch.app.shared.generated.resources.onboarding_intake_copy_body
import ketch.app.shared.generated.resources.onboarding_intake_notify
import ketch.app.shared.generated.resources.onboarding_intake_notify_body
import ketch.app.shared.generated.resources.onboarding_intake_select
import ketch.app.shared.generated.resources.onboarding_intake_select_body
import ketch.app.shared.generated.resources.onboarding_intake_share
import ketch.app.shared.generated.resources.onboarding_intake_share_body
import ketch.app.shared.generated.resources.onboarding_intake_start
import ketch.app.shared.generated.resources.onboarding_intake_title
import ketch.app.shared.generated.resources.onboarding_intake_torrents
import ketch.app.shared.generated.resources.onboarding_intake_torrents_body
import ketch.app.shared.generated.resources.onboarding_recommended
import ketch.app.shared.generated.resources.onboarding_step
import ketch.app.shared.generated.resources.onboarding_use_body
import ketch.app.shared.generated.resources.onboarding_use_control
import ketch.app.shared.generated.resources.onboarding_use_control_body
import ketch.app.shared.generated.resources.onboarding_use_download
import ketch.app.shared.generated.resources.onboarding_use_download_android
import ketch.app.shared.generated.resources.onboarding_use_download_ios
import ketch.app.shared.generated.resources.onboarding_use_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The phone and tablet apps' first screens, full screen over the canvas wash: where downloads go,
 * whether this device downloads or controls a computer, and how links reach Ketch from other
 * apps. Progress dots show the step, Back returns to the one before and Skip ends the flow.
 *
 * Ending it, by any of those ways, records [WELCOME_VERSION] as seen and calls [onDone], which
 * shows the app; choosing to control a computer also opens pairing.
 *
 * @param platform the platform, whose folder step and advice differ.
 * @param deviceKind the kind of device this is, which names it, such as "This phone".
 * @param welcome where the flow starts, for previews; a new flow starts on the folder.
 */
@Composable
internal fun WelcomeFlow(
  state: AppState,
  platform: WelcomePlatform,
  onDone: () -> Unit,
  modifier: Modifier = Modifier,
  deviceKind: LocalDeviceKind = localDeviceKind(),
  welcome: WelcomeState = rememberWelcomeState(),
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val motion = KetchTheme.motion
  val finish = { pair: Boolean ->
    state.appSettings.saveUi { it.copy(onboardingVersion = WELCOME_VERSION) }
    if (pair) state.showAddRemoteDialog = true
    onDone()
  }
  if (welcome.canGoBack) {
    NavigationBackHandler(
      state = rememberNavigationEventState(NavigationEventInfo.None),
      onBackCompleted = { welcome.back() },
    )
  }
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .fillMaxSize()
      .canvasWash(colors, EmberRadius)
      .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    TopRow(
      welcome = welcome,
      onSkip = { finish(false) },
      modifier = Modifier.widthIn(max = ContentMaxWidth).padding(horizontal = spacing.s2),
    )
    AnimatedContent(
      targetState = welcome.step,
      transitionSpec = {
        val forward = targetState.ordinal > initialState.ordinal
        val enter = tween<IntOffset>(motion.xlong, easing = motion.easeDecelerate)
        val exit = tween<IntOffset>(motion.long, easing = motion.easeAccelerate)
        val shift = { width: Int -> if (forward) width / SLIDE_DIVISOR else -width / SLIDE_DIVISOR }
        val slideIn = slideInHorizontally(enter, shift)
        val slideOut = slideOutHorizontally(exit) { -shift(it) }
        (slideIn + fadeIn(tween(motion.xlong))) togetherWith
          (slideOut + fadeOut(tween(motion.long)))
      },
      label = "welcomeStep",
      modifier = Modifier.weight(1f).fillMaxWidth(),
    ) { step ->
      when (step) {
        WelcomeStep.Folder -> FolderStep(state, platform, deviceKind, welcome)
        WelcomeStep.Use -> UseStep(platform, deviceKind) { use ->
          if (!welcome.choose(use)) finish(use == WelcomeUse.Control)
        }
        WelcomeStep.Intake -> IntakeStep(platform, onDone = { finish(false) })
      }
    }
  }
}

/** Back on the start, the progress dots in the middle and Skip on the end. */
@Composable
private fun TopRow(welcome: WelcomeState, onSkip: () -> Unit, modifier: Modifier = Modifier) {
  val density = KetchTheme.density
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.fillMaxWidth().heightIn(min = density.iconButtonTarget),
  ) {
    if (welcome.canGoBack) {
      KetchIconButton(
        icon = KetchIcon.ChevronLeft,
        onClick = { welcome.back() },
        contentDescription = stringResource(Res.string.action_back),
        modifier = Modifier.align(Alignment.CenterStart),
      )
    }
    ProgressDots(count = WelcomeStep.entries.size, current = welcome.index)
    if (welcome.step != WelcomeStep.entries.last()) {
      KetchButton(
        text = stringResource(Res.string.action_skip),
        onClick = onSkip,
        variant = KetchButtonVariant.Ghost,
        modifier = Modifier.align(Alignment.CenterEnd),
      )
    }
  }
}

/** One dot per step: the current one a wider accent pill, the others small and muted. */
@Composable
private fun ProgressDots(count: Int, current: Int) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val motion = KetchTheme.motion
  val description = stringResource(Res.string.onboarding_step, current + 1, count)
  Row(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.semantics { contentDescription = description },
  ) {
    repeat(count) { index ->
      val active = index == current
      val width by animateDpAsState(
        targetValue = if (active) spacing.s6 else spacing.s2,
        animationSpec = tween(motion.medium, easing = motion.easeStandard),
        label = "dotWidth",
      )
      Box(
        Modifier
          .height(spacing.s2)
          .width(width)
          .background(if (active) colors.accent else colors.borderStrong, KetchTheme.shapes.full),
      )
    }
  }
}

/**
 * A step's layout: its [header], title, body and [content] centered above its [actions], which
 * sit at the bottom. On a short screen, such as a phone on its side, they all scroll together.
 */
@Composable
private fun StepScaffold(
  title: String,
  body: String,
  header: (@Composable () -> Unit)? = null,
  actions: (@Composable ColumnScope.() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit = {},
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val typography = KetchTheme.typography
  BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .heightIn(min = maxHeight)
        .padding(horizontal = spacing.s6),
    ) {
      Spacer(Modifier.weight(1f))
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .widthIn(max = ContentMaxWidth)
          .fillMaxWidth()
          .padding(vertical = spacing.s6),
      ) {
        if (header != null) {
          header()
          Spacer(Modifier.height(spacing.s8))
        }
        Text(
          text = title,
          style = typography.largeTitle.copy(lineBreak = LineBreak.Heading),
          color = colors.textPrimary,
          textAlign = TextAlign.Center,
          modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(spacing.s3))
        Text(
          text = body,
          style = typography.body.copy(lineBreak = LineBreak.Heading),
          color = colors.textSecondary,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.s8))
        content()
      }
      Spacer(Modifier.weight(1f))
      if (actions != null) {
        Column(
          verticalArrangement = Arrangement.spacedBy(spacing.s2),
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
            .padding(bottom = spacing.s6),
          content = actions,
        )
      }
    }
  }
}

/**
 * Step 1: where downloads go. On Android, the Download folder through the document picker, for
 * HTTP and FTP downloads; on iOS, Ketch's folder in the Files app, which needs no choice.
 */
@Composable
private fun FolderStep(
  state: AppState,
  platform: WelcomePlatform,
  deviceKind: LocalDeviceKind,
  welcome: WelcomeState,
) {
  if (platform == WelcomePlatform.Ios) {
    StepScaffold(
      title = stringResource(Res.string.onboarding_files_title),
      body = stringResource(Res.string.onboarding_files_body),
      header = { SailLanesIllustration(width = SailLanesIllustrationDefaults.CompactWidth) },
      actions = {
        KetchButton(
          text = stringResource(Res.string.action_continue),
          onClick = { welcome.next() },
          size = KetchButtonSize.Large,
          modifier = Modifier.fillMaxWidth(),
        )
      },
    ) {
      FolderPath(
        icon = KetchIcon.Folder,
        text = filesPath(deviceKind),
      )
    }
    return
  }
  val picker = rememberFilePicker()
  val scope = rememberCoroutineScope()
  val chosen = welcome.folder
  val choose: () -> Unit = {
    scope.launch {
      welcome.chooseFolder(
        pick = { picker.pickFolder(DOWNLOAD_TREE) },
        apply = { folder -> useFolder(state, folder) },
      )
    }
  }
  StepScaffold(
    title = stringResource(Res.string.onboarding_folder_title),
    body = stringResource(Res.string.onboarding_folder_body),
    header = { SailLanesIllustration(width = SailLanesIllustrationDefaults.CompactWidth) },
    actions = {
      if (chosen == null) {
        KetchButton(
          text = stringResource(Res.string.onboarding_folder_use_download),
          onClick = choose,
          size = KetchButtonSize.Large,
          leadingIcon = KetchIcon.Folder,
          loading = welcome.choosingFolder,
          modifier = Modifier.fillMaxWidth(),
        )
        KetchButton(
          text = stringResource(Res.string.onboarding_folder_not_now),
          onClick = { welcome.next() },
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Large,
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        KetchButton(
          text = stringResource(Res.string.action_continue),
          onClick = { welcome.next() },
          size = KetchButtonSize.Large,
          modifier = Modifier.fillMaxWidth(),
        )
        KetchButton(
          text = stringResource(Res.string.onboarding_folder_choose_another),
          onClick = choose,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Large,
          loading = welcome.choosingFolder,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
  ) {
    val error = welcome.folderError
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s4),
    ) {
      when {
        error != null -> Note(
          icon = KetchIcon.Warning,
          text = error.resolve(),
          color = KetchTheme.colors.status.failed.color,
        )
        chosen != null -> FolderPath(
          icon = KetchIcon.CheckCircle,
          text = stringResource(
            Res.string.onboarding_folder_saving_to,
            folderNameText(chosen).resolve(),
          ),
          tint = KetchTheme.colors.status.completed.color,
        )
      }
      // The folder only takes HTTP and FTP downloads; torrents can't write to it.
      Note(icon = KetchIcon.Info, text = stringResource(Res.string.onboarding_folder_torrents))
    }
  }
}

/** Step 2: download here, or add downloads to a computer from here. */
@Composable
private fun UseStep(
  platform: WelcomePlatform,
  deviceKind: LocalDeviceKind,
  onChoose: (WelcomeUse) -> Unit,
) {
  val spacing = KetchTheme.spacing
  val ios = platform == WelcomePlatform.Ios
  StepScaffold(
    title = stringResource(Res.string.onboarding_use_title),
    body = stringResource(Res.string.onboarding_use_body),
  ) {
    val here: @Composable () -> Unit = {
      ChoiceCard(
        icon = if (deviceKind == LocalDeviceKind.Phone) KetchIcon.Phone else KetchIcon.Tablet,
        hue = FileTypeHue.Sky,
        title = stringResource(
          Res.string.onboarding_use_download,
          stringResource(deviceKind.inSentence),
        ),
        detail = if (ios) {
          stringResource(Res.string.onboarding_use_download_ios)
        } else {
          stringResource(Res.string.onboarding_use_download_android)
        },
        onClick = { onChoose(WelcomeUse.Download) },
      )
    }
    val control: @Composable () -> Unit = {
      ChoiceCard(
        icon = KetchIcon.Laptop,
        hue = FileTypeHue.Indigo,
        title = stringResource(Res.string.onboarding_use_control),
        detail = stringResource(Res.string.onboarding_use_control_body),
        recommended = ios,
        onClick = { onChoose(WelcomeUse.Control) },
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
      // iOS recommends the computer, so it comes first.
      if (ios) {
        control()
        here()
      } else {
        here()
        control()
      }
    }
  }
}

/** Step 3, when downloading here: how links reach Ketch, and when it asks to notify. */
@Composable
private fun IntakeStep(platform: WelcomePlatform, onDone: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  StepScaffold(
    title = stringResource(Res.string.onboarding_intake_title),
    body = stringResource(Res.string.onboarding_intake_body),
    actions = {
      KetchButton(
        text = stringResource(Res.string.onboarding_intake_start),
        onClick = onDone,
        size = KetchButtonSize.Large,
        modifier = Modifier.fillMaxWidth(),
      )
    },
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s5),
      modifier = Modifier
        .fillMaxWidth()
        .background(colors.surface, KetchTheme.shapes.lg)
        .border(HairlineWidth, colors.hairline, KetchTheme.shapes.lg)
        .padding(spacing.s4),
    ) {
      if (platform == WelcomePlatform.Android) {
        Way(
          icon = KetchIcon.Browser,
          hue = FileTypeHue.Sky,
          title = stringResource(Res.string.onboarding_intake_share),
          detail = stringResource(Res.string.onboarding_intake_share_body),
        )
        Way(
          icon = KetchIcon.Link,
          hue = FileTypeHue.Jade,
          title = stringResource(Res.string.onboarding_intake_select),
          detail = stringResource(Res.string.onboarding_intake_select_body),
        )
      } else {
        Way(
          icon = KetchIcon.Copy,
          hue = FileTypeHue.Sky,
          title = stringResource(Res.string.onboarding_intake_copy),
          detail = stringResource(Res.string.onboarding_intake_copy_body),
        )
      }
      Way(
        icon = KetchIcon.FileTorrent,
        hue = FileTypeHue.Violet,
        title = stringResource(Res.string.onboarding_intake_torrents),
        detail = stringResource(Res.string.onboarding_intake_torrents_body),
      )
      Way(
        icon = KetchIcon.Bell,
        hue = FileTypeHue.Amber,
        title = stringResource(Res.string.onboarding_intake_notify),
        detail = stringResource(Res.string.onboarding_intake_notify_body),
      )
    }
  }
}

/** A choice on the use step: a 64 dp card with a hue tile, a title and a line about it. */
@Composable
private fun ChoiceCard(
  icon: KetchIcon,
  hue: FileTypeHue,
  title: String,
  detail: String,
  onClick: () -> Unit,
  recommended: Boolean = false,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.lg
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = ChoiceMinHeight)
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(colors.surface)
      .background(overlay)
      .border(HairlineWidth, if (recommended) colors.accent else colors.hairline, shape)
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(horizontal = spacing.s4, vertical = spacing.s3),
  ) {
    KetchHueTile(icon = icon, hue = hue, size = KetchHueTileDefaults.Medium)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      if (recommended) {
        KetchBadge(
          stringResource(Res.string.onboarding_recommended),
          tone = KetchBadgeTone.Accent,
          modifier = Modifier.padding(bottom = spacing.s1),
        )
      }
      Text(text = title, style = KetchTheme.typography.bodyStrong, color = colors.textPrimary)
      Text(text = detail, style = KetchTheme.typography.caption, color = colors.textSecondary)
    }
    KetchIconImage(
      KetchIcon.Chevron,
      size = KetchTheme.density.controlGlyph,
      tint = colors.textTertiary,
    )
  }
}

/** One way links reach Ketch: a small hue tile beside a title and a line about it. */
@Composable
private fun Way(icon: KetchIcon, hue: FileTypeHue, title: String, detail: String) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
    KetchHueTile(icon = icon, hue = hue, size = KetchHueTileDefaults.Small)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      Text(text = title, style = KetchTheme.typography.bodyStrong, color = colors.textPrimary)
      Text(text = detail, style = KetchTheme.typography.caption, color = colors.textSecondary)
    }
  }
}

/** Where downloads go, as a sunken pill with a glyph. */
@Composable
private fun FolderPath(
  icon: KetchIcon,
  text: String,
  tint: Color = KetchTheme.colors.textSecondary,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .heightIn(min = KetchTheme.density.input)
      .background(colors.surfaceSunken, KetchTheme.shapes.full)
      .padding(horizontal = spacing.s4),
  ) {
    KetchIconImage(icon, size = KetchTheme.density.controlGlyph, tint = tint)
    Text(text = text, style = KetchTheme.typography.label, color = colors.textPrimary)
  }
}

/** A short line with a glyph, such as a caveat or, in [color], an error. */
@Composable
private fun Note(
  icon: KetchIcon,
  text: String,
  color: Color = KetchTheme.colors.textSecondary,
) {
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    KetchIconImage(icon, size = KetchTheme.density.controlGlyph, tint = color)
    Text(text = text, style = KetchTheme.typography.caption, color = color)
  }
}

/** Uses [folder] for the downloads of the embedded device, saved for the next launch. */
private suspend fun useFolder(state: AppState, folder: String) {
  val device = state.instances.value.firstOrNull { it is EmbeddedInstance }
  checkNotNull(device) { "This device can't download" }
  val config = device.instance.status().config
  state.settingsFor(device).updateDownload(config.copy(defaultDirectory = folder))
}

/** Where the Files app shows Ketch's downloads on this iPhone or iPad. */
@Composable
private fun filesPath(deviceKind: LocalDeviceKind): String {
  val device = if (deviceKind == LocalDeviceKind.IPad) "iPad" else "iPhone"
  return stringResource(Res.string.onboarding_files_path, device)
}

// The shared Download folder, where Android's folder picker opens.
private const val DOWNLOAD_TREE =
  "content://com.android.externalstorage.documents/tree/primary%3ADownload"

// Steps slide in by a quarter of the width.
private const val SLIDE_DIVISOR = 4

private val ContentMaxWidth = 480.dp
private val ChoiceMinHeight = 64.dp
private val HairlineWidth = 1.dp
private val EmberRadius = 520.dp
