package com.linroid.ketch.app.ui.onboarding

/** The mobile platforms that greet a new user with the [WelcomeFlow], which differ in places. */
internal enum class WelcomePlatform {
  /** Downloads can go to a folder chosen with the document picker, and run in the background. */
  Android,

  /** Downloads go to Ketch's folder in the Files app, and pause in the background. */
  Ios,
}

/** The platform the app runs on, or `null` where it shows no welcome flow (desktop, web). */
internal expect val welcomePlatform: WelcomePlatform?
