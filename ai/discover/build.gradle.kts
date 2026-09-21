plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
}

dependencies {
  api(projects.library.api)
  // AI settings live in the shared TOML config so the apps and the CLI
  // configure discovery through one type.
  api(projects.config)

  // Koog framework for LLM integration
  implementation(libs.koog.agents)
  // Gemini support; not part of the koog-agents aggregate.
  implementation(libs.koog.google.client)

  // Ktor client for fetching
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.contentNegotiation)
  implementation(libs.ktor.serialization.json)

  // Coroutines
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.client.mock)
}
