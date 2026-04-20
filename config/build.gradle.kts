@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.kotlinx.serialization)
}

kotlin {
  androidLibrary {
    namespace = "com.linroid.ketch.config"
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
      implementation(libs.okio)
      implementation(libs.ktoml.core)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
    wasmJsMain.dependencies {
      implementation(libs.kotlinx.browser)
    }
  }
}
