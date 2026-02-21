@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.sqldelight)
  alias(libs.plugins.mavenPublish)
}

kotlin {
  androidLibrary {
    namespace = "com.linroid.ketch.sqlite"
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

  sourceSets {
    commonMain.dependencies {
      api(projects.library.core)
      implementation(libs.sqldelight.runtime)
      implementation(libs.sqldelight.coroutines)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.io.core)
    }
    androidMain.dependencies {
      implementation(libs.sqldelight.android.driver)
    }
    iosMain.dependencies {
      implementation(libs.sqldelight.native.driver)
    }
    jvmMain.dependencies {
      implementation(libs.sqldelight.sqlite.driver)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

sqldelight {
  databases {
    create("KetchDatabase") {
      packageName.set("com.linroid.ketch.sqlite")
    }
  }
}
