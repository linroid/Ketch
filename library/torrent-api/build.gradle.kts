@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.mavenPublish)
}

// The torrent control contract: what clients of any backend, in-process or remote, use to inspect
// torrent tasks and send them commands. It has the targets of library:api, so browser clients can
// use it; the engine that implements it lives in library:torrent.
kotlin {
  android {
    namespace = "com.linroid.ketch.torrent.api"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
    }
    optimization {
      consumerKeepRules.apply {
        publish = true
        file("consumer-rules.pro")
      }
    }
  }

  iosArm64()
  iosSimulatorArm64()
  jvm()

  js {
    browser()
    nodejs()
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs { browser() }

  @OptIn(ExperimentalWasmDsl::class)
  wasmWasi { nodejs() }

  sourceSets {
    commonMain.dependencies {
      api(projects.library.api)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}
