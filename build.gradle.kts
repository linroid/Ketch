import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  // this is necessary to avoid the plugins to be loaded multiple times
  // in each subproject's classloader
  alias(libs.plugins.androidApplication) apply false
  alias(libs.plugins.androidLibrary) apply false
  alias(libs.plugins.androidKmpLibrary) apply false
  alias(libs.plugins.composeMultiplatform) apply false
  alias(libs.plugins.composeCompiler) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.composeHotReload) apply false
  alias(libs.plugins.sqldelight) apply false
  alias(libs.plugins.graalvmNative) apply false
  alias(libs.plugins.mavenPublish) apply false
}

// Published libraries support JVM 11+, while Gradle compiles with JDK 21. -Xjdk-release and
// --release also limit the JDK API to Java 11, so newer APIs fail at compile time. Tests keep
// the build JDK because they depend on unpublished JVM 21 modules such as :library:server.
subprojects {
  pluginManager.withPlugin("com.vanniktech.maven.publish") {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
      extensions.configure<KotlinMultiplatformExtension> {
        targets.withType<KotlinJvmTarget>().configureEach {
          compilations.named("main") {
            compileTaskProvider.configure {
              compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.add("-Xjdk-release=11")
              }
            }
            compileJavaTaskProvider?.configure { options.release.set(11) }
          }
        }
      }
    }
  }
}
