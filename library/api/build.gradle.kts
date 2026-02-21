@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.mavenPublish)
}

val gitRevision: String by lazy {
  providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
  }.standardOutput.asText.get().trim()
}

val generateVersion by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/version")
  val ver = findProperty("VERSION_NAME")?.toString() ?: "dev"
  val revision = gitRevision
  inputs.property("version", ver)
  inputs.property("revision", revision)
  outputs.dir(outputDir)
  doLast {
    val dir = outputDir.get().dir("com/linroid/ketch/api").asFile
    dir.mkdirs()
    dir.resolve("KetchBuildVersion.kt").writeText(
      """
      |package com.linroid.ketch.api
      |
      |internal const val KETCH_BUILD_VERSION = "$ver"
      |internal const val KETCH_BUILD_REVISION = "$revision"
      |""".trimMargin(),
    )
  }
}

kotlin {
  androidLibrary {
    namespace = "com.linroid.ketch.api"
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

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs { browser() }

  sourceSets {
    commonMain {
      kotlin.srcDir(generateVersion)
      dependencies {
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.datetime)
      }
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
    androidMain {
    }
  }
}
