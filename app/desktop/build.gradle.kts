import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeHotReload)
}

dependencies {
  implementation(projects.config)
  implementation(projects.app.shared)
  implementation(projects.ai.discover)
  implementation(projects.library.core)
  implementation(projects.library.ktor)
  implementation(projects.library.ftp)
  implementation(projects.library.torrent)
  implementation(projects.library.server)
  implementation(projects.library.sqlite)
  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutinesSwing)
  // SLF4J backend for Ktor server and Koog; much smaller than Logback.
  runtimeOnly(libs.slf4j.simple)

  testImplementation(libs.kotlin.test)
}

// sqlite-jdbc bundles its native library for ~30 OS/arch pairs (~25 MB). A desktop package only
// runs where it was built, so keep the build host's library and drop the rest.
abstract class StripForeignSqliteNatives : TransformAction<StripForeignSqliteNatives.Params> {
  interface Params : TransformParameters {
    // Folder under org/sqlite/native/ to keep, e.g. "Mac/aarch64".
    @get:Input val keep: Property<String>
  }

  @get:InputArtifact abstract val input: Provider<FileSystemLocation>

  override fun transform(outputs: TransformOutputs) {
    val jar = input.get().asFile
    if (!jar.name.startsWith("sqlite-jdbc-")) {
      outputs.file(input)
      return
    }
    val native = "org/sqlite/native/"
    val keep = "$native${parameters.keep.get()}/"
    ZipFile(jar).use { zip ->
      ZipOutputStream(outputs.file(jar.name).outputStream()).use { out ->
        for (entry in zip.entries()) {
          val name = entry.name
          // Keep the kept folder, its parent folders and everything outside native/.
          if (name.startsWith(native) && !name.startsWith(keep) && !keep.startsWith(name)) continue
          out.putNextEntry(ZipEntry(name).apply { time = entry.time })
          zip.getInputStream(entry).use { it.copyTo(out) }
          out.closeEntry()
        }
      }
    }
  }
}

// Folder names match sqlite-jdbc's OSInfo.
val hostSqliteNatives = providers.systemProperty("os.name").zip(
  providers.systemProperty("os.arch"),
) { os, arch ->
  val osDir = when {
    os.startsWith("Mac") -> "Mac"
    os.startsWith("Windows") -> "Windows"
    else -> "Linux"
  }
  val archDir = when (arch) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> arch
  }
  "$osDir/$archDir"
}

val sqliteNativesStripped =
  Attribute.of("ketch.sqliteNativesStripped", Boolean::class.javaObjectType)

dependencies {
  attributesSchema { attribute(sqliteNativesStripped) }
  artifactTypes.getByName("jar") { attributes.attribute(sqliteNativesStripped, false) }
  registerTransform(StripForeignSqliteNatives::class) {
    from.attribute(sqliteNativesStripped, false)
      .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    to.attribute(sqliteNativesStripped, true)
      .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    parameters.keep.set(hostSqliteNatives)
  }
}

configurations.runtimeClasspath {
  attributes.attribute(sqliteNativesStripped, true)
}

compose.desktop {
  application {
    mainClass = "com.linroid.ketch.app.desktop.MainKt"
    providers.gradleProperty("desktopJavaHome").orNull?.let { javaHome = it }

    buildTypes.release.proguard {
      configurationFiles.from(
        rootDir.resolve("app/proguard-rules.pro"),
        project.file("proguard-rules.pro"),
        project(":library:api").file("consumer-rules.pro"),
        project(":library:core").file("consumer-rules.pro"),
        project(":library:ftp").file("consumer-rules.pro"),
        project(":library:ktor").file("consumer-rules.pro"),
        project(":library:sqlite").file("consumer-rules.pro"),
        project(":library:remote").file("consumer-rules.pro"),
      )
    }

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      modules("java.sql")
      packageName = "Ketch"
      // Lists Ketch under "Open with" for .torrent files in Finder, Explorer and Linux file
      // managers; see main.kt for how each platform delivers the file.
      fileAssociation(
        mimeType = "application/x-bittorrent",
        extension = "torrent",
        description = "BitTorrent file",
      )

      macOS {
        iconFile.set(rootProject.file("art/icon.icns"))
      }
      windows {
        iconFile.set(rootProject.file("art/icon.ico"))
      }
      linux {
        iconFile.set(rootProject.file("art/icon.png"))
      }
      packageVersion = providers.gradleProperty("VERSION_NAME").get()
        .substringBefore("-")
        .let { semver ->
          // DMG/MSI require MAJOR > 0; default to 1.0.0 for dev builds
          val parts = semver.split(".")
          if (parts.first() == "0") "1.0.0" else semver
        }
    }
  }
}
