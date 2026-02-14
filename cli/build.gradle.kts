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
        "--initialize-at-build-time=io.ktor,kotlin,kotlinx.coroutines,kotlinx.serialization,kotlinx.io",
        "--initialize-at-build-time=ch.qos.logback",
        "--initialize-at-build-time=org.slf4j.LoggerFactory",
        "--initialize-at-build-time=org.slf4j.helpers.Reporter",
        "--initialize-at-run-time=kotlin.uuid.SecureRandomHolder",
        "-H:IncludeResources=web/.*",
        "-H:IncludeResources=logback.xml",
      )
    }
  }
}

val bundleWebApp by tasks.registering(Copy::class) {
  dependsOn(":app:web:wasmJsBrowserDistribution")
  from(project(":app:web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
  exclude("*.map", "*.LICENSE.txt")
  into(layout.buildDirectory.dir("generated/resources/web"))
  // Inject auto-connect flag so the bundled web UI connects to its
  // serving host automatically.
  filesMatching("index.html") {
    filter { line ->
      line.replace(
        "<head>",
        "<head>\n    <meta name=\"kdown-auto-connect\" content=\"true\">",
      )
    }
  }
}

sourceSets.main {
  resources.srcDir(bundleWebApp.map { layout.buildDirectory.dir("generated/resources") })
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
  implementation(libs.logback)
}
