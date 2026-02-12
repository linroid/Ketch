plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
  application
}

application {
  mainClass.set("com.linroid.kdown.cli.MainKt")
}

dependencies {
  implementation(projects.library.server)
  implementation(projects.library.core)
  implementation(projects.library.sqlite)
  implementation(projects.library.ktor)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktoml.core)
  implementation(libs.ktor.client.cio)
}
