plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
}

dependencies {
  api(projects.library.api)

  implementation(libs.koog.mcp.server)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}
