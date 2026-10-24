@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

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

val generateVersion = tasks.register("generateVersion") {
  val outputDir = layout.buildDirectory.dir("generated/version")
  val ver = findProperty("VERSION_NAME")?.toString() ?: "dev"
  // Test CI can keep this stable so unrelated commits do not invalidate every API consumer.
  // Normal builds and publications continue to embed the actual Git revision.
  val revision = providers.gradleProperty("testBuildRevision").orNull ?: gitRevision
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
  // Checks the public API against the dump in api/.
  @OptIn(ExperimentalAbiValidation::class)
  abiValidation()

  android {
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

  js {
    browser()
    nodejs()
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs { browser() }

  @OptIn(ExperimentalWasmDsl::class)
  wasmWasi { nodejs() }

  sourceSets {
    commonMain {
      kotlin.srcDir(generateVersion)
      dependencies {
        // The public API exposes their types (Flow, StateFlow, KSerializer, TimeZone).
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.serialization.json)
        api(libs.kotlinx.datetime)
      }
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
    androidMain {
    }
  }
}
