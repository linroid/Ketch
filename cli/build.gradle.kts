import org.apache.tools.ant.taskdefs.condition.Os
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

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
      // Optimizing for size needs GraalVM for JDK 23 or later. Older ones build with -Ob, which
      // optimizes less than the default -O2 and so makes a smaller binary.
      buildArgs.add(if (nativeImageJdk >= 23) "-Os" else "-Ob")
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

val bundleWebApp by tasks.registering(Sync::class) {
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
  // The wasm and JS (about 20 MB) go in gzipped: the native binary stores resources as they are,
  // and the server sends them compressed to browsers (KetchServer's webResources).
  val webDir = destinationDir
  doLast {
    webDir.walkTopDown()
      .filter { it.isFile && it.extension in setOf("wasm", "js") }
      .forEach { file ->
        val gzipped = File(file.parentFile, "${file.name}.gz")
        object : GZIPOutputStream(gzipped.outputStream()) {
          init {
            def.setLevel(Deflater.BEST_COMPRESSION)
          }
        }.use { out -> file.inputStream().use { it.copyTo(out) } }
        file.delete()
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

// Koog, kotlinx-schema and Ktor's server depend on kotlin-reflect for features the CLI does not
// use (reflective tool sets and schemas, loading server modules by name). The stdlib looks it up
// by name, so native-image would compile much of it in.
configurations.runtimeClasspath {
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
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
  implementation(projects.updater)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.client.cio)
  implementation(libs.logback)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}
