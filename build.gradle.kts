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
}

subprojects {
  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
      freeCompilerArgs.add("-Xexpect-actual-classes")
      optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
    }
  }

  plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
      repositories {
        maven {
          name = "mavenCentral"
          url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
          credentials {
            username = providers.gradleProperty("mavenCentralUsername")
              .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_mavenCentralUsername"))
              .orNull
            password = providers.gradleProperty("mavenCentralPassword")
              .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_mavenCentralPassword"))
              .orNull
          }
        }
      }
      publications.withType<MavenPublication> {
        pom {
          name.set("KDown ${project.name.replaceFirstChar { it.uppercase() }}")
          description.set(
            "KDown - Kotlin Multiplatform download manager library"
          )
          url.set("https://github.com/linroid/KDown")
          inceptionYear.set("2025")
          licenses {
            license {
              name.set("The Apache License, Version 2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
              distribution.set(
                "https://www.apache.org/licenses/LICENSE-2.0.txt"
              )
            }
          }
          developers {
            developer {
              id.set("linroid")
              name.set("linroid")
              url.set("https://github.com/linroid")
            }
          }
          scm {
            url.set("https://github.com/linroid/KDown")
            connection.set(
              "scm:git:git://github.com/linroid/KDown.git"
            )
            developerConnection.set(
              "scm:git:ssh://git@github.com/linroid/KDown.git"
            )
          }
        }
      }
    }

    val signingKey = providers.gradleProperty("signingInMemoryKey")
      .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey"))
      .orNull
    if (signingKey != null) {
      plugins.apply("signing")
      extensions.configure<SigningExtension> {
        val signingKeyId = providers.gradleProperty("signingInMemoryKeyId")
          .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKeyId"))
          .orNull
        val signingPassword =
          providers.gradleProperty("signingInMemoryKeyPassword")
            .orElse(
              providers.environmentVariable(
                "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword"
              )
            ).orNull
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(extensions.getByType<PublishingExtension>().publications)
      }
    }
  }
}
