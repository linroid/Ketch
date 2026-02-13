import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeHotReload)
}

dependencies {
  implementation(projects.app.shared)
  implementation(projects.library.server)
  implementation(projects.library.ktor)
  implementation(projects.library.sqlite)
  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutinesSwing)
  implementation(libs.logback)
  // Needed for ProGuard to resolve Netty's Log4J2Logger class hierarchy.
  // Shrunk away in the final output since nothing references it.
  runtimeOnly(libs.log4j.api)
}

compose.desktop {
  application {
    mainClass = "com.linroid.kdown.desktop.MainKt"

    buildTypes.release.proguard {
      configurationFiles.from(rootProject.file("proguard-rules.pro"))
    }

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      modules("java.sql")
      packageName = "KDown"
      packageVersion = providers.gradleProperty("kdown.version").get()
        .substringBefore("-")
        .let { semver ->
          // DMG/MSI require MAJOR > 0; default to 1.0.0 for dev builds
          val parts = semver.split(".")
          if (parts.first() == "0") "1.0.0" else semver
        }
    }
  }
}
