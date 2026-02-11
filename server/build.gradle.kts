plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
  application
}

application {
  mainClass.set("com.linroid.kdown.server.MainKt")
}

dependencies {
  api(projects.library.core)
  implementation(projects.library.ktor)
  implementation(projects.library.sqlite)

  implementation(libs.ktor.serverCore)
  implementation(libs.ktor.serverNetty)
  implementation(libs.ktor.serverContentNegotiation)
  implementation(libs.ktor.serverSse)
  implementation(libs.ktor.serverCors)
  implementation(libs.ktor.serverStatusPages)
  implementation(libs.ktor.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.serverTestHost)
  testImplementation(libs.ktor.client.cio)
  testImplementation(libs.ktor.client.contentNegotiation)
  testImplementation(libs.ktor.serialization.json)
}
