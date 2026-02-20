import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.mavenPublish)
}

kotlin {
  androidLibrary {
    namespace = "com.linroid.kdown.endpoints"
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
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.resources)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
