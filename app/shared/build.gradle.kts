import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
}

kotlin {
  androidLibrary {
    namespace = "com.linroid.kdown.app.shared"
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
      baseName = "KDownApp"
      isStatic = true
    }
  }

  jvm()

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.library.core)
      implementation(projects.library.remote)
      implementation(projects.library.ktor)
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
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.ktor.client.okhttp)
      implementation(libs.dnssd)
    }
    iosMain.dependencies {
      implementation(projects.library.sqlite)
      implementation(libs.ktor.client.darwin)
      implementation(libs.dnssd)
    }
    jvmMain.dependencies {
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
