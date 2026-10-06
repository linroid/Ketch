@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.mavenPublish)
}

kotlin {
  android {
    withHostTest {}
    namespace = "com.linroid.ketch.hls"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
    }
  }

  iosArm64()
  iosSimulatorArm64()

  jvm()

  js {
    nodejs()
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmWasi {
    nodejs()
  }

  sourceSets {
    commonMain.dependencies {
      api(projects.library.core)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
  // Some environments have Xcode CLI tools but no arm64 simulator SDK support.
  // Keep regular builds green by requiring explicit opt-in for simulator test execution.
  enabled = providers.gradleProperty("enableIosSimulatorTests").orNull == "true"
}
