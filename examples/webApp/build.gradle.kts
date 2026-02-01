import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
}

kotlin {
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser {
      commonWebpackConfig {
        outputFileName = "kdown-examples.js"
      }
    }
    binaries.executable()
  }

  sourceSets {
    wasmJsMain.dependencies {
      implementation(projects.examples.app)
    }
  }
}
