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
}

compose.desktop {
  application {
    mainClass = "com.linroid.ketch.app.desktop.MainKt"

    buildTypes.release.proguard {
      configurationFiles.from(
        rootDir.resolve("app/proguard-rules.pro"),
        project(":library:api").file("consumer-rules.pro"),
        project(":library:core").file("consumer-rules.pro"),
        project(":library:ktor").file("consumer-rules.pro"),
        project(":library:sqlite").file("consumer-rules.pro"),
        project(":library:remote").file("consumer-rules.pro"),
      )
    }

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      modules("java.sql")
      packageName = "Ketch"

      macOS {
        iconFile.set(rootProject.file("art/icon.icns"))
      }
      windows {
        iconFile.set(rootProject.file("art/icon.ico"))
      }
      linux {
        iconFile.set(rootProject.file("art/icon.png"))
      }
      packageVersion = providers.gradleProperty("VERSION_NAME").get()
        .substringBefore("-")
        .let { semver ->
          // DMG/MSI require MAJOR > 0; default to 1.0.0 for dev builds
          val parts = semver.split(".")
          if (parts.first() == "0") "1.0.0" else semver
        }
    }
  }
}
