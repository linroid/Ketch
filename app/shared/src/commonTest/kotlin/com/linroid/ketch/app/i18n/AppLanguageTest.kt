package com.linroid.ketch.app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLanguageTest {

  @Test
  fun appLanguageOf_regionsAndScripts_readTheirTranslation() {
    assertEquals("en", appLanguageOf("en-GB")?.tag)
    assertEquals("pt-BR", appLanguageOf("pt-PT")?.tag)
    assertEquals("de", appLanguageOf("de_AT")?.tag)
    assertEquals("es", appLanguageOf("es-419")?.tag)
  }

  @Test
  fun appLanguageOf_chinese_readsByScriptThenRegion() {
    assertEquals("zh-Hans", appLanguageOf("zh")?.tag)
    assertEquals("zh-Hans", appLanguageOf("zh-CN")?.tag)
    assertEquals("zh-Hans", appLanguageOf("zh-Hans-HK")?.tag)
    assertEquals("zh-Hant", appLanguageOf("zh-TW")?.tag)
    assertEquals("zh-Hant", appLanguageOf("zh-HK")?.tag)
    assertEquals("zh-Hant", appLanguageOf("zh-Hant-CN")?.tag)
  }

  @Test
  fun appLanguageOf_untranslatedOrMissing_isNull() {
    assertNull(appLanguageOf("it-IT"))
    assertNull(appLanguageOf(""))
    assertNull(appLanguageOf(null))
  }

  @Test
  fun appLanguages_eachTag_readsItself() {
    for (language in AppLanguages) assertEquals(language, appLanguageOf(language.tag))
  }
}
