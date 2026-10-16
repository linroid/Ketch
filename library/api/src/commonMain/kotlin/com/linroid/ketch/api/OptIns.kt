package com.linroid.ketch.api

/** Public only for Ketch's own modules; may change in any release. */
@RequiresOptIn(
  message = "Ketch's internal API; it may change in any release.",
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

/** Interfaces that are stable to call but not to implement: later versions may add members. */
@RequiresOptIn(
  message = "Ketch may add members to this interface in any release.",
  level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class KetchImplementationApi
