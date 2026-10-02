package com.linroid.ketch.app.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.shell.LocalKetchLayout
import com.linroid.ketch.app.ui.shell.ShellNavigation
import kotlinx.datetime.TimeZone

/**
 * The Devices page (`⌘0`), the overview of every device the app knows: a card for each with
 * what it is doing, in a grid of 300 dp or wider columns, under a sentence about all of them.
 * With a single device, or on a phone, an "Add a device" card follows; otherwise the header
 * offers to add or pair one. Phones name the page in their top bar, so it shows no title there.
 */
@Composable
fun DevicesScreen(state: AppState) {
  val devices by remember(state) { state.instanceManager.presence }.collectAsState()
  val instances by state.instances.collectAsState()
  val active by state.activeInstance.collectAsState()
  val work = rememberDeviceWork(remember(devices.map { it.entry }) { devices.map { it.entry } })
  val phone = LocalKetchLayout.current.navigation == ShellNavigation.Phone
  val padding = KetchTheme.density.pagePadding
  val spacing = KetchTheme.spacing
  val actions = rememberPageActions(state)
  // Presence lists a device once its first totals are in, a moment after launch. The add card
  // follows the configured devices, so it does not flash while the others load.
  val loading = devices.isEmpty() && instances.isNotEmpty()
  val addCard = phone || instances.size <= 1
  var renaming by remember { mutableStateOf<DevicePresence?>(null) }
  var removing by remember { mutableStateOf<DevicePresence?>(null) }
  val sentence = fleetSentence(
    devices = devices,
    work = work,
    mode = devices.firstOrNull { it.entry is EmbeddedInstance }?.speedMode ?: SpeedMode.Full,
    now = LocalClock.current.now(),
    timeZone = TimeZone.currentSystemDefault(),
  )
  Column(Modifier.fillMaxSize()) {
    if (!phone) PageHeader(sentence, actions.takeUnless { addCard })
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
      val gap = spacing.s4
      val width = maxWidth - padding * 2
      val fits = ((width + gap) / (MinCardWidth + gap)).toInt()
      val columns = if (phone) 1 else fits.coerceAtLeast(1)
      Column(
        verticalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = padding)
          .padding(top = if (phone) padding else spacing.s2, bottom = padding),
      ) {
        if (phone) {
          Text(
            text = sentence,
            style = KetchTheme.typography.bodyS,
            color = KetchTheme.colors.textSecondary,
          )
        }
        if (loading) {
          CardGrid(count = instances.size, columns = columns, gap = gap) { _, modifier ->
            CardPlaceholder(modifier)
          }
          return@Column
        }
        val count = devices.size + if (addCard) 1 else 0
        CardGrid(count = count, columns = columns, gap = gap) { index, modifier ->
          val device = devices.getOrNull(index)
          if (device == null) {
            AddDeviceCard(actions, modifier)
          } else {
            key(device.deviceId) {
              DeviceCard(
                state = state,
                device = device,
                work = work[device.deviceId] ?: DeviceWork(),
                active = device.deviceId == active?.deviceId,
                marked = device.deviceId == active?.deviceId && devices.size > 1,
                onRename = { renaming = device },
                onRemove = { removing = device },
                modifier = modifier,
              )
            }
          }
        }
      }
    }
  }
  renaming?.let { RenameDeviceDialog(state, it, onDismiss = { renaming = null }) }
  removing?.let { RemoveDeviceDialog(state, it, onDismiss = { removing = null }) }
}

/**
 * What the page offers besides the cards: adding a device, and pairing another one with this
 * device where it can be shared.
 */
private class PageActions(val onAdd: () -> Unit, val onPair: (() -> Unit)?)

@Composable
private fun rememberPageActions(state: AppState): PageActions {
  val canShare = state.instanceManager.isLocalServerSupported
  return remember(state, canShare) {
    PageActions(onAdd = state::addDevice, onPair = if (canShare) state::pairDevice else null)
  }
}

/** "Devices" and the sentence about every device, with [actions] at the end when given. */
@Composable
private fun PageHeader(sentence: String, actions: PageActions?) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = KetchTheme.density.pagePadding)
      .padding(top = spacing.s3, bottom = spacing.s2),
  ) {
    Column(Modifier.weight(1f)) {
      Text(text = "Devices", style = KetchTheme.typography.pageTitle, color = colors.textPrimary)
      Text(
        text = sentence,
        style = KetchTheme.typography.bodyS,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (actions != null) {
      actions.onPair?.let { onPair ->
        KetchButton(
          text = PairLabel,
          onClick = onPair,
          variant = KetchButtonVariant.Secondary,
          leadingIcon = KetchIcon.QrCode,
        )
      }
      KetchButton(text = "Add device", onClick = actions.onAdd, leadingIcon = KetchIcon.Plus)
    }
  }
}

/**
 * [count] cells in rows of [columns], [gap] apart; the cells of a row share its tallest one's
 * height, so the cards line up.
 */
@Composable
private fun CardGrid(
  count: Int,
  columns: Int,
  gap: Dp,
  cell: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(gap)) {
    for (start in 0 until count step columns) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
      ) {
        for (index in start until start + columns) {
          val modifier = Modifier.weight(1f).fillMaxHeight()
          if (index < count) cell(index, modifier) else Spacer(modifier)
        }
      }
    }
  }
}

/** Where a device's card will be, while its first numbers load. */
@Composable
private fun CardPlaceholder(modifier: Modifier) {
  Spacer(
    modifier
      .height(PlaceholderHeight)
      .background(KetchTheme.colors.surfaceSunken, KetchTheme.shapes.card)
  )
}

/**
 * The dashed card that asks for another device: what can be added, then Add device and pairing
 * with this device. The Add device sheet finds devices on the network itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddDeviceCard(actions: PageActions, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing.s3, Alignment.CenterVertically),
    modifier = modifier
      .dashedBorder(colors.borderStrong, KetchTheme.shapes.card)
      .padding(spacing.s6),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(spacing.s10).background(colors.accentSoft, KetchTheme.shapes.full),
    ) {
      KetchIconImage(KetchIcon.Fleet, size = KetchTheme.density.navGlyph, tint = colors.accentText)
    }
    Text(text = "Add a device", style = type.titleM, color = colors.textPrimary)
    Text(
      text = buildAnnotatedString {
        append("Control a NAS or another computer from here. Run ")
        withStyle(SpanStyle(fontFamily = type.mono.fontFamily)) { append("ketch\u00A0server") }
        append(" there, or turn on Settings › Sharing in its Ketch app.")
      },
      style = type.bodyS,
      color = colors.textSecondary,
      textAlign = TextAlign.Center,
    )
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.padding(top = spacing.s1),
    ) {
      KetchButton(
        text = "Add device",
        onClick = actions.onAdd,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Plus,
        modifier = Modifier.width(IntrinsicSize.Max),
      )
      actions.onPair?.let { onPair ->
        KetchButton(
          text = PairLabel,
          onClick = onPair,
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.QrCode,
          modifier = Modifier.width(IntrinsicSize.Max),
        )
      }
    }
  }
}

// A computer pairs a phone that controls it; a phone pairs a computer the same way.
private val PairLabel: String get() = if (isMobilePlatform) "Pair a computer" else "Pair a phone"

private val MinCardWidth: Dp = 300.dp
private val PlaceholderHeight: Dp = 236.dp
