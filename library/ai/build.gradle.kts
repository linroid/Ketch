plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
}

dependencies {
  api(projects.library.api)

  // Koog framework for LLM integration
  implementation(libs.koog.agents)

  // Ktor client for fetching
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.contentNegotiation)
  implementation(libs.ktor.serialization.json)

  // Coroutines
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}
