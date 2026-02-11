plugins {
  alias(libs.plugins.kotlinJvm)
  application
}

application {
  mainClass.set("com.linroid.kdown.cli.MainKt")
}

dependencies {
  implementation(projects.library.ktor)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.ktor.client.cio)
}
