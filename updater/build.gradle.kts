plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
}

dependencies {
  api(projects.library.core)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(projects.library.ktor)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}
