package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpDownloadSource
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class KetchFeaturesTest {
  @Test
  fun status_optionalSourceFeatures_onlyAdvertisedWhenRegistered() = runTest {
    val base = setOf(KetchFeatures.AUTO_CONNECTIONS, KetchFeatures.QUEUE_POSITION,
      KetchFeatures.REQUEST_ID, KetchFeatures.PROXY)
    val dispatcher = StandardTestDispatcher(testScheduler)
    for (features in listOf(emptySet(), setOf(KetchFeatures.FINITE_HLS),
      setOf(KetchFeatures.FINITE_DASH),
      setOf(KetchFeatures.FINITE_HLS, KetchFeatures.FINITE_DASH))) {
      val engine = FakeHttpEngine()
      val sources = features.map { feature ->
        object : DownloadSource by HttpDownloadSource(engine) {
          override val features: Set<String> = setOf(feature)
        }
      }
      val ketch = Ketch(
        httpEngine = engine,
        // Android host tests have no application context to resolve the default folder.
        config = DownloadConfig(defaultDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()),
        additionalSources = sources,
        dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
      )
      try {
        val compatibility = if (features.size == 2) setOf(KetchFeatures.FINITE_MEDIA) else emptySet()
        assertEquals(base + features + compatibility, ketch.status().features)
      } finally {
        ketch.close()
      }
    }
  }
}
