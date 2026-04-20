@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.mavenPublish)
}

kotlin {
  androidLibrary {
    namespace = "com.linroid.ketch.torrent"
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

  // Intermediate source set shared between JVM and Android
  applyDefaultHierarchyTemplate()
  sourceSets {
    val jvmAndAndroidMain by creating {
      dependsOn(commonMain.get())
    }
    androidMain.get().dependsOn(jvmAndAndroidMain)
    jvmMain.get().dependsOn(jvmAndAndroidMain)

    commonMain.dependencies {
      api(projects.library.core)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    named("jvmAndAndroidMain") {
      dependencies {
        implementation(libs.libtorrent4j)
      }
    }
    jvmMain.dependencies {
      runtimeOnly(libs.libtorrent4j.macos)
      runtimeOnly(libs.libtorrent4j.linux)
      runtimeOnly(libs.libtorrent4j.windows)
    }
    androidMain.dependencies {
      runtimeOnly(libs.libtorrent4j.android.arm64)
      runtimeOnly(libs.libtorrent4j.android.arm)
      runtimeOnly(libs.libtorrent4j.android.x86)
      runtimeOnly(libs.libtorrent4j.android.x8664)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
  enabled = providers.gradleProperty("enableIosSimulatorTests").orNull == "true"
}
