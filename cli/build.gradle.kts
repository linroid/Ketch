plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.graalvmNative)
  application
}

application {
  mainClass.set("com.linroid.kdown.cli.MainKt")
}

graalvmNative {
  toolchainDetection.set(true)
  binaries {
    named("main") {
      imageName.set("kdown")
      mainClass.set("com.linroid.kdown.cli.MainKt")
      javaLauncher.set(
        project.extensions.getByType<JavaToolchainService>().launcherFor {
          languageVersion.set(JavaLanguageVersion.of(21))
          vendor.set(JvmVendorSpec.ORACLE)
        }
      )
      buildArgs.addAll(
        "--no-fallback",
        "-H:+ReportExceptionStackTraces",
        "--initialize-at-build-time=kotlin",
        "--initialize-at-run-time=kotlin.uuid.SecureRandomHolder",
      )
    }
  }
}

dependencies {
  implementation(projects.library.server)
  implementation(projects.library.core)
  implementation(projects.library.sqlite)
  implementation(projects.library.ktor)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktoml.core)
  implementation(libs.ktor.client.cio)
}
