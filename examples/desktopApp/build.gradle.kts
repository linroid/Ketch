import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
}

dependencies {
  implementation(projects.examples.app)
  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutinesSwing)
}

compose.desktop {
  application {
    mainClass = "com.linroid.kdown.examples.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "KDown Examples"
      packageVersion = "1.0.0"
    }
  }
}
