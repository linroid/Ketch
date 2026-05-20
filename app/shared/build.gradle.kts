import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.kotlinx.serialization)
}

kotlin {
  android {
    namespace = "com.linroid.ketch.app.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
    }
  }

  listOf(
    iosArm64(),
    iosSimulatorArm64()
  ).forEach { iosTarget ->
    iosTarget.binaries.framework {
      baseName = "KetchApp"
      isStatic = true
    }
  }

  // Compose Multiplatform 1.11+ references iOS 18 APIs (e.g. UIViewLayoutRegion in
  // compose-ui-uikit) and dnssd 1.1.0 is built for iOS 15+. Kotlin/Native defaults to
  // iOS 14.0, which causes link failures and missing back-deployment dylib lookups at
  // runtime. Override the minimum iOS version to 18.0.
  targets.withType<KotlinNativeTarget>().configureEach {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions.freeCompilerArgs.add(
          "-Xoverride-konan-properties=" +
            "osVersionMin.ios_arm64=18.0;" +
            "osVersionMin.ios_simulator_arm64=18.0",
        )
      }
    }
  }

  jvm()

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.config)
      implementation(projects.library.remote)

      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(libs.compose.material3.adaptive)
      implementation(libs.compose.material3.adaptive.navigationSuite)
      implementation(compose.materialIconsExtended)
      implementation(libs.compose.ui)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.androidx.lifecycle.viewmodelCompose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
    androidMain.dependencies {
      implementation(projects.library.core)
      implementation(projects.library.ktor)
      implementation(projects.ai.discover)
      implementation(projects.library.ftp)
      implementation(projects.library.torrent)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.ktor.client.okhttp)
      implementation(libs.dnssd)
    }
    iosMain.dependencies {
      implementation(projects.library.core)
      implementation(projects.library.ktor)
      implementation(projects.library.ftp)
      implementation(projects.library.torrent)
      implementation(projects.library.sqlite)
      implementation(libs.ktor.client.darwin)
      implementation(libs.dnssd)
    }
    jvmMain.dependencies {
      implementation(projects.library.core)
      implementation(projects.library.ktor)
      implementation(projects.ai.discover)
      implementation(projects.library.ftp)
      implementation(projects.library.torrent)
      implementation(projects.library.sqlite)
      implementation(libs.kotlinx.coroutinesSwing)
      implementation(libs.ktor.client.cio)
      implementation(libs.dnssd)
    }
    wasmJsMain.dependencies {
      implementation(libs.ktor.client.js)
    }
  }
}
