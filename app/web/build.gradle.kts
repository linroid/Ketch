import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec

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
        outputFileName = "ketch-web.js"
      }
    }
    binaries.executable()
  }

  sourceSets {
    wasmJsMain.dependencies {
      implementation(projects.app.shared)
      implementation(projects.config)
      implementation(libs.compose.ui)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
    }
  }
}

tasks.named<Copy>("wasmJsProcessResources") {
  from(rootProject.file("app/shared/src/commonMain/composeResources/files/licenses")) {
    into("licenses")
  }
}

// Workaround: ktoml 0.7.1 generates Wasm code that binaryen's validator
// rejects (type mismatch in TomlMainEncoder.appendValue). Skip validation
// until ktoml ships a Kotlin 2.3-compatible release.
tasks.withType<BinaryenExec>().configureEach {
  binaryenArgs.add("--no-validation")
}
