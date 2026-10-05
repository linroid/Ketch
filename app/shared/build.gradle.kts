import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.Locale

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.kotlinx.serialization)
}

kotlin {
  android {
    namespace = "com.linroid.ketch.app.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    androidResources {
      enable = true
    }

    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
    }
  }

  listOf(
    iosArm64(),
    iosSimulatorArm64()
  ).forEach { iosTarget ->
    iosTarget.binaries.framework {
      baseName = "KetchApp"
      isStatic = true
    }
  }

  // Compose Multiplatform 1.11+ references iOS 18 APIs (e.g. UIViewLayoutRegion in
  // compose-ui-uikit) and dnssd 1.1.0 is built for iOS 15+. Kotlin/Native defaults to
  // iOS 14.0, which causes link failures and missing back-deployment dylib lookups at
  // runtime. Override the minimum iOS version to 18.0.
  targets.withType<KotlinNativeTarget>().configureEach {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions.freeCompilerArgs.add(
          "-Xoverride-konan-properties=" +
            "osVersionMin.ios_arm64=18.0;" +
            "osVersionMin.ios_simulator_arm64=18.0",
        )
      }
    }
  }

  jvm()

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    // The targets that run the engine inside the app: Android, desktop and iOS.
    val nonWebMain by creating {
      dependsOn(commonMain.get())
    }
    val jvmAndAndroidMain by creating {
      dependsOn(nonWebMain)
    }
    androidMain.get().dependsOn(jvmAndAndroidMain)
    jvmMain.get().dependsOn(jvmAndAndroidMain)
    iosMain.get().dependsOn(nonWebMain)

    commonMain.dependencies {
      api(projects.config)
      // FileLogger takes okio paths.
      api(libs.okio)
      implementation(projects.library.remote)

      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.datetime)
      // The Discover history file.
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.qrose)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(libs.compose.material3.adaptive)
      implementation(libs.compose.ui)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.androidx.lifecycle.viewmodelCompose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
      implementation(libs.androidx.navigationevent.compose)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
    nonWebMain.dependencies {
      implementation(libs.dnssd)
    }
    jvmAndAndroidMain.dependencies {
      implementation(projects.ai.discover)
    }
    androidMain.dependencies {
      implementation(libs.androidx.core)
      // Result launchers for the folder and file pickers.
      implementation(libs.androidx.activity.compose)
      implementation(projects.library.core)
      implementation(projects.library.ktor)
      implementation(projects.library.ftp)
      implementation(projects.library.torrent)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.ktor.client.okhttp)
    }
    iosMain.dependencies {
      implementation(projects.library.core)
      implementation(projects.library.ktor)
      implementation(projects.library.ftp)
      implementation(projects.library.torrent)
      implementation(projects.library.sqlite)
      implementation(libs.ktor.client.darwin)
    }
    jvmMain.dependencies {
      implementation(projects.library.core)
      implementation(projects.library.ktor)
      implementation(projects.library.ftp)
      implementation(projects.library.torrent)
      implementation(projects.library.sqlite)
      implementation(libs.kotlinx.coroutinesSwing)
      implementation(libs.ktor.client.cio)
    }
    wasmJsMain.dependencies {
      implementation(libs.ktor.client.js)
    }
    jvmTest.dependencies {
      // Skia for this machine, which the snapshot harness renders with.
      implementation(compose.desktop.currentOs)
    }
  }
}

// Chinese is written once per script, Simplified in values-zh and Traditional in values-zh-rTW.
// Browsers and Windows report a region without a script, so Traditional is copied for Hong Kong
// and Macau. Systems that report a script, such as iOS, get a copy of each under its script tag:
// lookup tries the script before the region, so zh-Hans-HK reads Simplified and zh-Hant reads
// Traditional, and it never crosses from one script to the other on its own.
val composeResourcesWithAliases = layout.buildDirectory.dir("generated/composeResourcesWithAliases")
val aliasComposeResources = tasks.register<Sync>("aliasComposeResources") {
  val source = layout.projectDirectory.dir("src/commonMain/composeResources")
  from(source)
  for (alias in listOf("values-b+zh+Hant", "values-zh-rHK", "values-zh-rMO")) {
    from(source.dir("values-zh-rTW")) { into(alias) }
  }
  from(source.dir("values-zh")) { into("values-b+zh+Hans") }
  into(composeResourcesWithAliases)
}

compose.resources {
  customDirectory(
    sourceSetName = "commonMain",
    directoryProvider = aliasComposeResources.map { composeResourcesWithAliases.get() },
  )
}

// `-PupdateTokenAllowlist` makes the design token guard rewrite its allowlist instead of failing.
tasks.named<Test>("jvmTest") {
  val updateTokenAllowlist = providers.gradleProperty("updateTokenAllowlist")
    .map { it != "false" }
    .getOrElse(false)
  inputs.property("updateTokenAllowlist", updateTokenAllowlist)
  outputs.upToDateWhen { !updateTokenAllowlist }
  outputs.cacheIf { !updateTokenAllowlist }
  systemProperty("updateTokenAllowlist", updateTokenAllowlist.toString())
  // The render tests pace their frames in real time, so one JVM spends minutes mostly waiting.
  // Test classes share no state, and each fork starts its own Compose scenes.
  maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
  // Tests read the English strings and format numbers the English way, whatever the machine's
  // language and region. `-PsnapshotLocale=de-DE` renders the snapshots in another language
  // instead; tests that assert English text then fail, so run only the snapshots with it.
  val locale = providers.gradleProperty("snapshotLocale").map(Locale::forLanguageTag)
    .getOrElse(Locale.US)
  inputs.property("snapshotLocale", locale.toLanguageTag())
  for (category in listOf("", ".display", ".format")) {
    systemProperty("user.language$category", locale.language)
    systemProperty("user.country$category", locale.country)
    systemProperty("user.script$category", locale.script)
  }
  // The guard reads the source text itself, comments included, not the compiled classes.
  inputs.dir("src/commonMain/kotlin")
    .withPropertyName("tokenGuardSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // `-Psnapshots` renders the UI snapshot scenarios to build/snapshots; without it they skip.
  val snapshots = providers.gradleProperty("snapshots").map { it != "false" }.getOrElse(false)
  inputs.property("snapshots", snapshots)
  if (snapshots) {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    systemProperty("ketch.snapshots", "true")
    systemProperty("ketch.snapshots.dir", layout.buildDirectory.dir("snapshots").get().asFile.path)
    // No window, no Dock icon and no reading the real clipboard.
    systemProperty("java.awt.headless", "true")
  }
}
