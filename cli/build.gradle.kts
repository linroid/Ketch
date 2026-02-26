import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.graalvmNative)
  application
}

application {
  mainClass.set("com.linroid.ketch.cli.MainKt")
}

graalvmNative {
  toolchainDetection.set(true)
  binaries {
    named("main") {
      imageName.set("ketch")
      mainClass.set("com.linroid.ketch.cli.MainKt")
      javaLauncher.set(
        project.extensions.getByType<JavaToolchainService>().launcherFor {
          languageVersion.set(JavaLanguageVersion.of(21))
          vendor.set(JvmVendorSpec.ORACLE)
        }
      )
      buildArgs.addAll(
        "--no-fallback",
        "-Ob",
        "-H:+ReportExceptionStackTraces",
        "--initialize-at-build-time=io.ktor,kotlin,kotlinx.coroutines,kotlinx.serialization,okio",
        "--initialize-at-build-time=ch.qos.logback",
        "--initialize-at-build-time=org.slf4j",
        "--initialize-at-run-time=kotlin.uuid.SecureRandomHolder",
        "-H:IncludeResources=web/.*",
        "-H:IncludeResources=logback.xml",
      )
      if (!Os.isFamily(Os.FAMILY_MAC)) {
        buildArgs.add("-H:+StripDebugInfo")
      }
    }
  }
}

// Pre-built web assets directory. When set (e.g. from CI), the
// wasmJsBrowserDistribution task is skipped and assets are copied
// from this path instead.
val prebuiltWebDir = providers.gradleProperty("prebuiltWebDir")
  .map { layout.projectDirectory.dir(it) }

val webSourceDir = if (prebuiltWebDir.isPresent) {
  prebuiltWebDir.get()
} else {
  project(":app:web").layout.buildDirectory
    .dir("dist/wasmJs/productionExecutable").get()
}

val bundleWebApp by tasks.registering(Copy::class) {
  if (!prebuiltWebDir.isPresent) {
    dependsOn(":app:web:wasmJsBrowserDistribution")
  }
  from(webSourceDir)
  exclude("*.map", "*.LICENSE.txt")
  into(layout.buildDirectory.dir("generated/resources/web"))
  // Inject auto-connect flag so the bundled web UI connects to its
  // serving host automatically.
  filesMatching("index.html") {
    filter { line ->
      line.replace(
        "<head>",
        "<head>\n    <meta name=\"ketch-auto-connect\" content=\"true\">",
      )
    }
  }
}

sourceSets.main {
  resources.srcDir(bundleWebApp.map { layout.buildDirectory.dir("generated/resources") })
}

dependencies {
  implementation(projects.config)
  implementation(projects.library.server)
  implementation(projects.ai.discover)
  implementation(projects.library.core)
  implementation(projects.library.sqlite)
  implementation(projects.library.ktor)
  implementation(projects.library.ftp)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.client.cio)
  implementation(libs.logback)
}
