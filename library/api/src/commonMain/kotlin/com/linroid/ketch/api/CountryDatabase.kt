package com.linroid.ketch.api

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * The IP to country database an instance names its connections' countries with
 * ([ActiveConnection.country]), downloaded on request. `ketch.controller(CountryDatabase)` returns
 * it where the instance lists [KetchFeatures.COUNTRIES].
 */
interface CountryDatabase {
  /** Where the database stands. */
  suspend fun status(): CountryDatabaseStatus

  /**
   * Starts downloading the database, replacing the one there is, unless a download runs, and
   * returns at once with the [CountryDatabaseStatus.State.DOWNLOADING] status.
   */
  suspend fun download(): CountryDatabaseStatus

  companion object Key : ControlKey<CountryDatabase> {
    override val name: String = "countries"
  }
}

/**
 * Where a [CountryDatabase] stands.
 *
 * @property updatedAt when the loaded database was downloaded
 * @property error why the last download or read failed, for [State.FAILED]
 */
@Serializable
data class CountryDatabaseStatus(
  val state: State,
  val updatedAt: Instant? = null,
  val error: String? = null,
) {
  @Serializable
  enum class State {
    /** Not downloaded yet. */
    MISSING,

    /** Being downloaded or read. */
    DOWNLOADING,

    /** Loaded: connections carry their countries. */
    READY,

    /** The last download or read failed. */
    FAILED,
  }
}
