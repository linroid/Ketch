package com.linroid.ketch.app.ui.settings

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.linroid.ketch.app.snapshot.SettingsFrame
import com.linroid.ketch.app.snapshot.SnapshotTheme
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.sendKey
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.snapshot.withSettings
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.PageAccessMode
import com.linroid.ketch.config.PageAccessSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Discover's Page access group driven through the rendered page, as the desktop shows it. */
class AiDiscoverySettingsRenderTest {
  @Test
  fun trustedSiteChip_remove_stopsTrustingThatSiteOnly() {
    runDiscoverPage(trusted = listOf("ubuntu.com", "blender.org")) { ai ->
      nodes().first { it.description() == "Remove ubuntu.com" }.click()
      frames(FRAMES)

      assertEquals(listOf("blender.org"), ai.settings.access.trustedSites)
    }
  }

  @Test
  fun addSiteField_add_trustsTheTypedSitesAndKeepsTheEngine() {
    runDiscoverPage(trusted = listOf("blender.org")) { ai ->
      val provider = assertNotNull(ai.provider)

      typeIntoAddField("https://www.Ubuntu.com/download github.com")
      clickAdd()

      assertEquals(
        listOf("blender.org", "ubuntu.com", "github.com"),
        ai.settings.access.trustedSites
      )
      assertTrue(PLACEHOLDER in texts(), "The field empties once its sites are added")
      assertSame(provider, ai.provider)
    }
  }

  @Test
  fun addSiteField_enterWithAWordWithoutADot_keepsTheWordWithAnError() {
    runDiscoverPage { ai ->
      typeIntoAddField("nas ubuntu.com")
      sendKey(Key.Enter)
      frames(FRAMES)

      assertEquals(listOf("ubuntu.com"), ai.settings.access.trustedSites)
      assertTrue("nas" in fieldTexts(), "What is not a site stays in the field: ${fieldTexts()}")
      assertTrue(INVALID in texts(), "The field explains what it needs: ${texts()}")
    }
  }

  @Test
  fun accessModeMenu_choice_savesItAndShowsItsHint() {
    runDiscoverPage { ai ->
      nodes().first { it.ownText() == "Ask for each new site" }.click()
      frames(FRAMES)
      nodes().last { it.ownText() == "Ask every time" }.click()
      frames(FRAMES)

      assertEquals(PageAccessMode.AskEveryTime, ai.settings.access.mode)
      assertTrue("Asks before every page and file check." in texts())
    }
  }

  @Test
  fun switchOff_disablesEverythingButTheSwitch() {
    runDiscoverPage(trusted = listOf("ubuntu.com")) { ai ->
      ai.setEnabled(false)
      frames(FRAMES)

      assertTrue(field(PLACEHOLDER).isDisabled(), "The page access field follows the switch")
      assertTrue(control("Edit").isDisabled(), "The providers follow the switch")
      assertTrue(control("Ask for each new site").isDisabled(), "So does the page access menu")
      assertFalse(discoverSwitch().isDisabled(), "The switch itself stays usable")

      discoverSwitch().click()
      frames(FRAMES)

      assertTrue(ai.settings.enabled)
      assertFalse(field(PLACEHOLDER).isDisabled())
      assertEquals(listOf("ubuntu.com"), ai.settings.access.trustedSites, "Nothing is lost")
    }
  }

  @Test
  fun addProvider_chooseOpenAiAndTypeItsKey_addsItWithoutSwitchingToIt() {
    runDiscoverPage { ai ->
      nodes().first { it.ownText() == "Add provider" }.click()
      frames(FRAMES)
      nodes().first { it.ownText() == "OpenAI" }.click()
      frames(FRAMES)
      typeInto(field("sk-…"), "sk-typed")
      nodes().last { it.ownText() == "Add" }.click()
      frames(FRAMES)

      assertEquals(listOf("anthropic", "openai"), ai.settings.providers.map { it.id })
      assertEquals("sk-typed", ai.settings.entry("openai")?.apiKey)
      assertEquals("anthropic", ai.settings.llm.id, "The provider in use stays in use")
      assertTrue("Add provider" in texts(), "The dialog closes")
    }
  }

  @Test
  fun editProvider_renameAndAddAModel_savesBoth() {
    runDiscoverPage { ai ->
      nodes().first { it.ownText() == "Edit" }.click()
      frames(FRAMES)
      typeInto(field("Anthropic"), "Work")
      typeInto(field("Add a model id"), "claude-next")
      sendKey(Key.Enter)
      frames(FRAMES)
      nodes().last { it.ownText() == "Save" }.click()
      frames(FRAMES)

      val saved = ai.settings.llm
      assertEquals("Work", saved.name)
      assertEquals(listOf("claude-next"), saved.models)
      assertEquals(LlmProvider.Anthropic.defaultModel, saved.effectiveModel)
    }
  }

  /** Renders the Discover page with [trusted] sites and runs [test] on it. */
  private fun runDiscoverPage(
    trusted: List<String> = emptyList(),
    test: suspend ImageComposeScene.(AiSettingsController) -> Unit,
  ) {
    val access = PageAccessSettings(trustedSites = trusted)
    withSettings(SnapshotTheme.Light, KetchDensity.Compact, access = access) { environment ->
      val state = environment.controller.state
      withScene(
        width = WIDTH,
        height = HEIGHT,
        content = {
          SettingsFrame(environment, SnapshotTheme.Light, KetchDensity.Compact, desktop = true) {
            SettingsContent(state, SettingsTarget(SettingsTarget.Page.Discover), onClose = {})
          }
        },
      ) {
        frames(FRAMES)
        test(state.aiSettings)
      }
    }
  }

  /** The field that adds sites, found by its placeholder while it is empty. */
  private fun ImageComposeScene.addField(): SemanticsNode = nodes().first { node ->
    SemanticsActions.SetText in node.config && node.subtree().any { it.ownText() == PLACEHOLDER }
  }

  /**
   * The field showing [placeholder], usable or not: a disabled field cannot be edited, so it
   * has no SetText.
   */
  private fun ImageComposeScene.field(placeholder: String): SemanticsNode = nodes().first { node ->
    (SemanticsActions.SetText in node.config || SemanticsProperties.Disabled in node.config) &&
      node.subtree().any { it.ownText() == placeholder }
  }

  /** The row that switches discovery on and off. */
  private fun ImageComposeScene.discoverSwitch(): SemanticsNode = nodes().first { node ->
    SemanticsProperties.ToggleableState in node.config &&
      node.subtree().any { it.ownText() == "AI discovery" }
  }

  /** The control around the text [label]. */
  private fun ImageComposeScene.control(label: String): SemanticsNode =
    checkNotNull(nodes().first { it.ownText() == label }.clickableAround())

  private fun SemanticsNode.clickableAround(): SemanticsNode? {
    var node: SemanticsNode? = this
    while (node != null && SemanticsActions.OnClick !in node.config) node = node.parent
    return node
  }

  private fun SemanticsNode.isDisabled(): Boolean = SemanticsProperties.Disabled in config

  /** Puts the keyboard in the add field and types [text] into it. */
  private suspend fun ImageComposeScene.typeIntoAddField(text: String) {
    val field = addField()
    field.config[SemanticsActions.RequestFocus].action?.invoke()
    field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
    frames(FRAMES)
  }

  /** Puts the keyboard in [field] and types [text] into it. */
  private suspend fun ImageComposeScene.typeInto(field: SemanticsNode, text: String) {
    field.config[SemanticsActions.RequestFocus].action?.invoke()
    field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
    frames(FRAMES)
  }

  private suspend fun ImageComposeScene.clickAdd() {
    nodes().first { it.ownText() == "Add" }.click()
    frames(FRAMES)
  }

  /** What each field holds, in order. */
  private fun ImageComposeScene.fieldTexts(): List<String> =
    nodes().filter { SemanticsActions.SetText in it.config }.map { it.text() }

  /** Every text shown. */
  private fun ImageComposeScene.texts(): Set<String> = nodes().mapNotNull { it.ownText() }.toSet()

  private fun SemanticsNode.ownText(): String? =
    config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

  /** What a field holds. */
  private fun SemanticsNode.text(): String =
    config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

  private fun SemanticsNode.description(): String? =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

  private fun SemanticsNode.subtree(): List<SemanticsNode> =
    listOf(this) + children.flatMap { it.subtree() }

  /** Clicks [this] node, or the closest clickable one around it. */
  private fun SemanticsNode.click() {
    var node: SemanticsNode? = this
    while (node != null && SemanticsActions.OnClick !in node.config) node = node.parent
    val action = node?.config?.get(SemanticsActions.OnClick)?.action
    assertTrue(action != null, "Nothing to click around $this")
    action()
  }

  private companion object {
    const val WIDTH = 860
    const val HEIGHT = 1600
    const val FRAMES = 12
    const val PLACEHOLDER = "Add a site, such as ubuntu.com"
    const val INVALID = "Enter a website's name, such as ubuntu.com."
  }
}
