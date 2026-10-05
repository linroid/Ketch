import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  id("dev.nucleusframework") version "2.5.0"
}

dependencies {
  implementation(projects.app.shared)
  implementation(projects.library.core)
  implementation(projects.library.ktor)
  implementation(compose.desktop.currentOs)
  implementation("dev.nucleusframework:nucleus.nucleus-application:2.5.0")
  implementation("dev.nucleusframework:nucleus.decorated-window-tao:2.5.0")
  // kotlin-logging 8.0.01's no-Logback substitution targets a removed class. Use the
  // same backend as the native CLI to avoid that incompatible substitution.
  runtimeOnly(libs.logback)
}

nucleus.application {
  mainClass = "com.linroid.ketch.app.nativeimage.MainKt"
  graalvm {
    isEnabled = true
    imageName = "ketch-native-prototype"
    optimization = NativeImageOptimization.QUICK_BUILD
    maxHeapSize = "256m"
  }
  nativeDistributions {
    packageName = "Ketch Native Prototype"
    packageVersion = "1.0.0"
    macOS {
      bundleID = "com.linroid.ketch.nativeprototype"
    }
  }
}
