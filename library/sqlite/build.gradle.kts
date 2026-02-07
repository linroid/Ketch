import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.sqldelight)
}

kotlin {
  androidLibrary {
    namespace = "com.linroid.kdown.sqlite"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
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
    create("KDownDatabase") {
      packageName.set("com.linroid.kdown.sqlite")
    }
  }
}
