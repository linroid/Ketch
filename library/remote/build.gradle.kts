import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.kotlinx.serialization)
}

kotlin {
  androidLibrary {
    namespace = "com.linroid.kdown.remote"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
    }
  }

  iosArm64()
  iosSimulatorArm64()
  jvm()

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      api(projects.library.api)
      api(projects.library.endpoints)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.datetime)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.contentNegotiation)
      implementation(libs.ktor.client.resources)
      implementation(libs.ktor.serialization.json.common)
    }
    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
    }
    iosMain.dependencies {
      implementation(libs.ktor.client.darwin)
    }
    jvmMain.dependencies {
      implementation(libs.ktor.client.cio)
    }
    wasmJsMain.dependencies {
      implementation(libs.ktor.client.js)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
  }
}
