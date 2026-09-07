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
  android {
    withHostTest {}
    withDeviceTest {
      applicationId = "com.linroid.ketch.torrent.test"
      instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    namespace = "com.linroid.ketch.torrent"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
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
      implementation(libs.okio)
      implementation(libs.ktor.network)
      implementation(libs.ktor.http)
      implementation(projects.library.ktor)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    jvmMain.dependencies {
      // OS filesystem calls only; no torrent implementation or torrent native bindings.
      implementation("net.java.dev.jna:jna:5.19.1")
    }
    jvmTest.dependencies {
      implementation(projects.library.server)
      implementation(projects.library.remote)
      implementation(libs.ktor.client.cio)
      implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
      runtimeOnly("org.libtorrent4j:libtorrent4j-macos:2.1.0-39")
      runtimeOnly("org.libtorrent4j:libtorrent4j-linux:2.1.0-39")
      runtimeOnly("org.libtorrent4j:libtorrent4j-windows:2.1.0-39")
    }
    named("androidDeviceTest") {
      dependencies {
        implementation(libs.androidx.testExt.junit)
        implementation("androidx.test:runner:1.7.0")
      }
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

// Explicit opt-in inputs make external-client and package smoke runs reproducible under Gradle.
tasks.withType<Test>().configureEach {
  for (name in listOf("TRANSMISSION_DAEMON", "KETCH_TORRENT_BENCHMARK",
    "KETCH_NATIVE_CLI", "KETCH_JVM_CLI")) {
    val value = providers.environmentVariable(name).orElse("")
    inputs.property(name, value)
    environment(name, value.get())
  }
}

// Independent-client test dependencies must never escape into published runtime variants.
tasks.register("verifyNoNativeTorrentRuntime") {
  val runtime = configurations.matching {
    it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") &&
      !it.name.contains("test", ignoreCase = true)
  }
  doLast {
    check(runtime.isNotEmpty()) { "No product runtime configurations were checked" }
    runtime.forEach { configuration ->
      val forbidden = configuration.incoming.resolutionResult.allComponents.filter {
        it.moduleVersion?.group == "org.libtorrent4j"
      }
      check(forbidden.isEmpty()) { "Torrent native dependency in ${configuration.name}: $forbidden" }
    }
  }
}
