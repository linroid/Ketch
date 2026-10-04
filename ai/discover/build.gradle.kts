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
  // Only the modules discovery uses; the koog-agents aggregate also bundles Bedrock (AWS SDK),
  // OpenTelemetry and Apache HttpClient, which bloat the apps.
  implementation(libs.koog.agents.core)
  implementation(libs.koog.anthropic.client)
  implementation(libs.koog.openai.client)
  implementation(libs.koog.ollama.client)
  implementation(libs.koog.google.client)
  // Koog's HTTP client implementation, found through ServiceLoader.
  runtimeOnly(libs.koog.http.client.ktor)

  // Ktor client for fetching. SafeFetcher uses the OkHttp engine because,
  // unlike CIO, it accepts a custom DNS resolver. The search and LLM clients
  // use the default engine of the app (on Android, OkHttp alone).
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.contentNegotiation)
  implementation(libs.ktor.serialization.json)

  // Coroutines
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.client.mock)
}
