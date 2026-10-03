package com.linroid.ketch.app.i18n

import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.KetchConfig
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLanguageSettingTest {
  private val saved = Locale.getDefault()

  @AfterTest fun restoreLocale() {
    Locale.setDefault(saved)
  }

  @Test fun saveLanguage_onTheDesktop_keepsItInConfigAndSetsTheLocale() {
    val store = MemoryConfigStore()
    val settings = AppSettingsController(store)

    settings.saveLanguage("zh-Hant")

    assertEquals("zh-Hant", settings.language)
    assertEquals("zh-Hant", store.config.appearance.language)
    assertEquals(Locale.forLanguageTag("zh-Hant"), Locale.getDefault())

    settings.saveLanguage(null)

    assertNull(settings.language)
    assertNull(store.config.appearance.language)
    assertEquals(saved, Locale.getDefault())
  }

  @Test fun appSettings_savedLanguage_appliesWhenTheAppStarts() {
    val store = MemoryConfigStore()
    store.save(store.config.copy(appearance = store.config.appearance.copy(language = "ja")))

    val settings = AppSettingsController(store)

    assertEquals("ja", settings.language)
    assertEquals(Locale.forLanguageTag("ja"), Locale.getDefault())
  }

  @Test fun appSettings_unknownSavedLanguage_followsTheSystem() {
    val store = MemoryConfigStore()
    store.save(store.config.copy(appearance = store.config.appearance.copy(language = "it")))

    assertNull(AppSettingsController(store).language)
    assertEquals(saved, Locale.getDefault())
  }

  private class MemoryConfigStore : ConfigStore {
    var config = KetchConfig()
    override fun load(): KetchConfig = config
    override fun save(config: KetchConfig) {
      this.config = config
    }
  }
}
