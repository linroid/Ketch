package com.linroid.ketch.api

/**
 * Marks declarations that are public only so Ketch's own modules can share them, such as its
 * logging helpers. They are not part of the supported API and may change or go away in any
 * release.
 */
@RequiresOptIn(
  message = "This is Ketch's internal API, shared between its modules; it may change in any " +
    "release.",
  level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.TYPEALIAS,
)
annotation class InternalKetchApi

/**
 * Marks interfaces that Ketch's own modules implement, such as [KetchApi] and [DownloadTask].
 * Calling them is supported; implementing them, as a test fake does, needs this opt-in because
 * later versions may add members an implementation then has to provide.
 */
@RequiresOptIn(
  message = "Ketch may add members to this interface in any release, which implementations " +
    "then have to provide.",
  level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class KetchImplementationApi
