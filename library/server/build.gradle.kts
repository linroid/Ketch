plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
}

dependencies {
  api(projects.library.core)
  api(projects.library.endpoints)
  implementation(projects.library.ktor)
  implementation(projects.library.sqlite)

  implementation(libs.ktor.serverCore)
  implementation(libs.ktor.serverCio)
  implementation(libs.ktor.serverContentNegotiation)
  implementation(libs.ktor.serverResources)
  implementation(libs.ktor.serverSse)
  implementation(libs.ktor.serverAuth)
  implementation(libs.ktor.serverCors)
  implementation(libs.ktor.serverStatusPages)
  implementation(libs.ktor.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.dnssd)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.serverTestHost)
  testImplementation(libs.ktor.client.cio)
  testImplementation(libs.ktor.client.contentNegotiation)
  testImplementation(libs.ktor.serialization.json)
}
