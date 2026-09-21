@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.mavenPublish)
}

kotlin {
  android {
    namespace = "com.linroid.ketch.ktor"
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

  iosArm64()
  iosSimulatorArm64()

  jvm()

  sourceSets {
    commonMain.dependencies {
      api(projects.library.core)
      implementation(libs.ktor.client.core)
    }
    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
    }
    iosMain.dependencies {
      implementation(libs.ktor.client.darwin)
    }
    jvmMain.dependencies {
      implementation(libs.ktor.client.cio)
      implementation(libs.ktor.client.okhttp)
    }
    wasmJsMain.dependencies {
      implementation(libs.ktor.client.js)
    }
    jvmTest.dependencies {
      implementation(projects.library.sqlite)
      implementation(libs.sqldelight.runtime)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.ktor.client.mock)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

// Public fixtures are deliberately excluded from ordinary offline test runs.
tasks.withType<Test>().configureEach {
  val publicDownloads = providers.gradleProperty("publicDownloadTests").orNull == "true"
  inputs.property("publicDownloadTests", publicDownloads)
  outputs.upToDateWhen { !publicDownloads }
  outputs.cacheIf { !publicDownloads }
  if (!publicDownloads) {
    filter.excludeTestsMatching("*PublicDownloadTest")
  }
}
