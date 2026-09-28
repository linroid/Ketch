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

// Oracle GraalVM for JDK 21 stops at 21.0.9 on macOS Intel, which cannot read the current
// reachability metadata, so the release builds that target with -PnativeImageJdk=25.
val nativeImageJdk = providers.gradleProperty("nativeImageJdk").map(String::toInt).getOrElse(21)

graalvmNative {
  toolchainDetection.set(true)
  binaries {
    named("main") {
      imageName.set("ketch")
      mainClass.set("com.linroid.ketch.cli.MainKt")
      javaLauncher.set(
        project.extensions.getByType<JavaToolchainService>().launcherFor {
          languageVersion.set(JavaLanguageVersion.of(nativeImageJdk))
          vendor.set(JvmVendorSpec.ORACLE)
        }
      )
      buildArgs.addAll(
        "--no-fallback",
        "-Ob",
        "-H:+ReportExceptionStackTraces",
        "--initialize-at-build-time=io.ktor,kotlin,kotlinx.coroutines,kotlinx.serialization,kotlinx.io,okio",
        "--initialize-at-build-time=ch.qos.logback",
        "--initialize-at-build-time=org.slf4j",
        // Logback parses logback.xml at build time and keeps SAX helper objects from it.
        "--initialize-at-build-time=org.xml.sax.helpers",
        // Ktor's OkHttp engine (under io.ktor) switches over this enum, so its
        // build-time WhenMappings class initializes it, along with its companion.
        "--initialize-at-build-time=okhttp3.Protocol,okhttp3.Protocol\$Companion",
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
  exclude("*.map")
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

val releaseLicenses = rootProject.layout.projectDirectory
  .dir("app/shared/src/commonMain/composeResources/files/licenses")

tasks.processResources {
  from(releaseLicenses) { into("licenses") }
}

distributions.main {
  contents {
    from(releaseLicenses) { into("licenses") }
  }
}

val prepareNativeLicenses by tasks.registering(Sync::class) {
  from(releaseLicenses)
  into(layout.buildDirectory.dir("native/nativeCompile/licenses"))
}

// The native plugin clears its output directory before building the executable.
// Restore the sidecar notices after compilation, including when the image is up to date.
tasks.named("nativeCompile") {
  finalizedBy(prepareNativeLicenses)
}

dependencies {
  implementation(projects.config)
  implementation(projects.library.server)
  implementation(projects.library.mcp)
  implementation(projects.ai.discover)
  implementation(projects.library.core)
  implementation(projects.library.sqlite)
  implementation(projects.library.ktor)
  implementation(projects.library.ftp)
  implementation(projects.library.torrent)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.client.cio)
  implementation(libs.logback)
}
