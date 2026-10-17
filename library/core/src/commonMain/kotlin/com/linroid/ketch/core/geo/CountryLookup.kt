package com.linroid.ketch.core.geo

import com.linroid.ketch.api.CountryDatabase

/**
 * A [CountryDatabase] that [com.linroid.ketch.core.Ketch] names its connections' countries with.
 */
interface CountryLookup : CountryDatabase {
  /**
   * The ISO 3166 code of [host]'s country, such as `DE`; `null` for reserved and unknown
   * addresses, and for a host name until it resolves. Asked for every connection each time they
   * are sampled, so it answers from memory and resolves names in the background.
   */
  fun countryOf(host: String): String?
}
