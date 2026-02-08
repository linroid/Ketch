import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.kotlinx.serialization)
}

kotlin {
  androidLibrary {
    namespace = "com.linroid.kdown.core"
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
  wasmJs {
    browser {
      testTask {
        // kotlinx.io.files.Path requires Node.js APIs unavailable in browser tests
        enabled = false
      }
    }
  }

  sourceSets {
    commonMain.dependencies {
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      api(libs.kotlinx.io.core)
      implementation(libs.kotlinx.datetime)
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
