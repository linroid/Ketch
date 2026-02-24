package com.linroid.ketch.ai.site

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * In-memory store for site profiles and the user's allowlist.
 */
class SiteProfileStore internal constructor() {

  private val mutex = Mutex()
  private val sites = mutableMapOf<String, SiteEntry>()

  /**
   * Adds a site to the allowlist with its [profile].
   */
  suspend fun add(
    domain: String,
    profile: SiteProfile,
  ): SiteEntry {
    val entry = SiteEntry(
      domain = domain,
      addedAt = Instant.fromEpochMilliseconds(
        System.currentTimeMillis()
      ),
      profile = profile,
    )
    mutex.withLock {
      sites[domain] = entry
    }
    return entry
  }

  /**
   * Removes a site from the allowlist.
   *
   * @return `true` if the site was found and removed
   */
  suspend fun remove(domain: String): Boolean {
    return mutex.withLock {
      sites.remove(domain) != null
    }
  }

  /** Returns all sites in the allowlist. */
  suspend fun getAll(): List<SiteEntry> {
    return mutex.withLock {
      sites.values.toList()
    }
  }

  /** Returns the site entry for [domain], or `null`. */
  suspend fun get(domain: String): SiteEntry? {
    return mutex.withLock {
      sites[domain]
    }
  }

  /**
   * Updates the profile for an existing site.
   *
   * @return updated entry, or `null` if not found
   */
  suspend fun updateProfile(
    domain: String,
    profile: SiteProfile,
  ): SiteEntry? {
    return mutex.withLock {
      val existing = sites[domain] ?: return@withLock null
      val updated = existing.copy(profile = profile)
      sites[domain] = updated
      updated
    }
  }

  /**
   * Allowlist entry combining metadata and profile.
   */
  data class SiteEntry(
    val domain: String,
    val addedAt: Instant,
    val profile: SiteProfile,
  )
}
