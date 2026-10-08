import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
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
  implementation(projects.library.hls)
  implementation(projects.library.dash)
  implementation(projects.library.torrent)
  implementation(projects.library.remote)
  implementation(projects.library.server)
  implementation(projects.library.sqlite)
  implementation(projects.updater)
  implementation(compose.desktop.currentOs)
  // The menu bar, tray and dialog strings in src/main/composeResources.
  implementation(libs.compose.components.resources)
  implementation(libs.kotlinx.coroutinesSwing)
  implementation(libs.kotlinx.serialization.json)
  // Hides the Dock icon on macOS while no window is open (MacDock).
  implementation(libs.jna)
  // SLF4J backend for Ktor server and Koog; much smaller than Logback.
  runtimeOnly(libs.slf4j.simple)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

// Chinese is written once per script and copied for the systems that name it by script or by
// another region, as app/shared/build.gradle.kts does for the shared strings.
val composeResourcesWithAliases = layout.buildDirectory.dir("generated/composeResourcesWithAliases")
val aliasComposeResources = tasks.register<Sync>("aliasComposeResources") {
  val source = layout.projectDirectory.dir("src/main/composeResources")
  from(source)
  for (alias in listOf("values-b+zh+Hant", "values-zh-rHK", "values-zh-rMO")) {
    from(source.dir("values-zh-rTW")) { into(alias) }
  }
  from(source.dir("values-zh")) { into("values-b+zh+Hans") }
  into(composeResourcesWithAliases)
}

compose.resources {
  customDirectory(
    sourceSetName = "main",
    directoryProvider = aliasComposeResources.map { composeResourcesWithAliases.get() },
  )
}

// The languages of the desktop's strings, as macOS names them: English and one per values-*
// folder. macOS shows its own parts of an app, such as the app menu's Services, Hide and Quit and
// the Open and Save dialogs, only in the languages the app's bundle lists; see
// docs/development/localization.md.
val macLocalizations = listOf("en") +
  layout.projectDirectory.dir("src/main/composeResources").asFile.list().orEmpty()
    .filter { it.startsWith("values-") }
    .sorted()
    .map { macLocalization(it.removePrefix("values-")) }

// A resource folder's qualifier as macOS names its language: "ja" for ja, "pt-BR" for pt-rBR,
// and Chinese by script: "zh-Hant" for Taiwan, Hong Kong and Macau, else "zh-Hans".
fun macLocalization(qualifier: String): String {
  val language = qualifier.substringBefore("-r")
  val region = qualifier.substringAfter("-r", "")
  return when {
    language == "zh" -> if (region in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
    region.isEmpty() -> language
    else -> "$language-$region"
  }
}

tasks.test {
  // Tests read the English strings and format numbers the English way, whatever the machine's
  // language and region.
  for (category in listOf("", ".display", ".format")) {
    systemProperty("user.language$category", "en")
    systemProperty("user.country$category", "US")
    systemProperty("user.script$category", "")
  }
}

// Some runtime jars bundle native libraries for systems other than the one they are named for. A
// desktop package only runs where it was built, so keep the build host's libraries and drop the
// rest:
// - sqlite-jdbc bundles its library for ~30 OS/arch pairs (~25 MB).
// - Skiko's runtime jar for macOS arm64 also carries the x64 library (22 MB). Compose unpacks the
//   host's library from the jar but ships the jar, so the other one would be dead weight.
// - JNA bundles its native library for ~30 OS/arch pairs.
abstract class StripForeignNatives : TransformAction<StripForeignNatives.Params> {
  interface Params : TransformParameters {
    // Folder under org/sqlite/native/ to keep, e.g. "Mac/aarch64".
    @get:Input val sqlite: Property<String>

    // Skiko's name for the host, e.g. "macos-arm64".
    @get:Input val skiko: Property<String>

    // JNA's folder for the host under com/sun/jna/, e.g. "darwin-aarch64".
    @get:Input val jna: Property<String>
  }

  @get:InputArtifact abstract val input: Provider<FileSystemLocation>

  override fun transform(outputs: TransformOutputs) {
    val jar = input.get().asFile
    val keep = when {
      jar.name.startsWith("sqlite-jdbc-") -> keepSqlite(parameters.sqlite.get())
      jar.name.startsWith("skiko-awt-runtime-") -> keepSkiko(parameters.skiko.get())
      jar.name.startsWith("jna-") -> keepJna(parameters.jna.get())
      else -> {
        outputs.file(input)
        return
      }
    }
    ZipFile(jar).use { zip ->
      ZipOutputStream(outputs.file(jar.name).outputStream()).use { out ->
        for (entry in zip.entries()) {
          val name = entry.name
          if (!keep(name)) continue
          out.putNextEntry(ZipEntry(name).apply { time = entry.time })
          zip.getInputStream(entry).use { it.copyTo(out) }
          out.closeEntry()
        }
      }
    }
  }

  // The kept folder, its parent folders and everything outside native/.
  private fun keepSqlite(folder: String): (String) -> Boolean {
    val native = "org/sqlite/native/"
    val kept = "$native$folder/"
    return { name -> !name.startsWith(native) || name.startsWith(kept) || kept.startsWith(name) }
  }

  // Everything but the native folders of other systems: one per OS/arch under com/sun/jna/.
  private fun keepJna(host: String): (String) -> Boolean {
    val native = Regex("""com/sun/jna/([^/]+-[^/]+)/.+""")
    return { name -> native.matchEntire(name)?.let { it.groupValues[1] == host } ?: true }
  }

  // Everything but the libraries (and their checksums) of other systems.
  private fun keepSkiko(host: String): (String) -> Boolean {
    val library = Regex("""(lib)?skiko-(\w+-\w+)\.(dylib|so|dll)(\.sha256)?""")
    return { name -> library.matchEntire(name)?.let { it.groupValues[2] == host } ?: true }
  }
}

val hostOs = providers.systemProperty("os.name").map { os ->
  when {
    os.startsWith("Mac") -> "mac"
    os.startsWith("Windows") -> "windows"
    else -> "linux"
  }
}
val hostArm = providers.systemProperty("os.arch").map { it == "aarch64" || it == "arm64" }

// Folder names match sqlite-jdbc's OSInfo.
val hostSqliteNatives = hostOs.zip(providers.systemProperty("os.arch")) { os, arch ->
  val osDir = when (os) {
    "mac" -> "Mac"
    "windows" -> "Windows"
    else -> "Linux"
  }
  val archDir = when (arch) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> arch
  }
  "$osDir/$archDir"
}

// Names match JNA's Platform.RESOURCE_PREFIX.
val hostJnaNatives = hostOs.zip(hostArm) { os, arm ->
  "${if (os == "mac") "darwin" else if (os == "windows") "win32" else os}-" +
    if (arm) "aarch64" else "x86-64"
}

// Names match Skiko's hostOs and hostArch ids.
val hostSkikoNatives = hostOs.zip(hostArm) { os, arm ->
  "${if (os == "mac") "macos" else os}-${if (arm) "arm64" else "x64"}"
}

val foreignNativesStripped =
  Attribute.of("ketch.foreignNativesStripped", Boolean::class.javaObjectType)

dependencies {
  attributesSchema { attribute(foreignNativesStripped) }
  artifactTypes.getByName("jar") { attributes.attribute(foreignNativesStripped, false) }
  registerTransform(StripForeignNatives::class) {
    from.attribute(foreignNativesStripped, false)
      .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    to.attribute(foreignNativesStripped, true)
      .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    parameters.sqlite.set(hostSqliteNatives)
    parameters.skiko.set(hostSkikoNatives)
    parameters.jna.set(hostJnaNatives)
  }
}

configurations.runtimeClasspath {
  attributes.attribute(foreignNativesStripped, true)
  // Koog, kotlinx-schema and Ktor's server depend on kotlin-reflect for features the app does not
  // use (reflective tool sets and schemas, loading server modules by name), and Compose's ProGuard
  // rules keep all of kotlin.**, so it would ship whole (3.4 MB).
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// ProGuard's mapping of the release, under the build directory.
val proguardMapping = "outputs/proguard/mapping.txt"

// Rules for the release's ProGuard run, beside the files listed below:
// - ProGuard renames classes and methods, so stack traces from a release can only be read with
//   the mapping of that build. It is written to build/outputs/proguard/mapping.txt, which the
//   release workflow publishes beside the packages.
// - The rules libraries ship in META-INF/proguard, which R8 applies on Android but Compose's
//   ProGuard task does not, such as Ktor's and kotlinx.coroutines' for their atomic fields.
// - The names of the services in META-INF/services: ServiceLoader finds a service's providers by
//   the name of its interface. proguard-rules.pro keeps the providers the app uses.
val proguardRules by tasks.registering {
  val libraries = objects.fileCollection().from(configurations.runtimeClasspath)
  val mapping = layout.buildDirectory.file(proguardMapping)
  val rules = layout.buildDirectory.file("generated/proguard/rules.pro")
  inputs.files(libraries).withPropertyName("libraries")
  inputs.property("mapping", mapping.map { it.asFile.invariantSeparatorsPath })
  outputs.file(rules)
  doLast {
    val mappingFile = mapping.get().asFile
    mappingFile.parentFile.mkdirs()
    val text = StringBuilder("-printmapping '${mappingFile.invariantSeparatorsPath}'\n")
    for (jar in libraries.files.filter { it.name.endsWith(".jar") }.sortedBy { it.name }) {
      ZipFile(jar).use { zip ->
        val entries = zip.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }
        for (entry in entries) {
          if (entry.name.startsWith("META-INF/proguard/")) {
            text.append("\n# ${jar.name}: ${entry.name}\n")
            text.append(zip.getInputStream(entry).reader().readText()).append('\n')
          } else if (entry.name.startsWith("META-INF/services/")) {
            text.append("-keepnames class ${entry.name.removePrefix("META-INF/services/")}\n")
          }
        }
      }
    }
    rules.get().asFile.writeText(text.toString())
  }
}

/**
 * The class data sharing (AppCDS) archive of a desktop app image, [FILE_NAME] beside its jars: the
 * classes a launch loads, parsed and laid out at build time, which the JVM maps from the file
 * instead of loading them again. Its read-only part stays clean memory the system shares and can
 * drop, so the app uses about 50 to 65 MB less and opens its window about a third sooner. The
 * launcher's java-options point at it; a JVM that finds no archive there, as in `./gradlew run`,
 * loads the classes as before.
 *
 * An archive only works with the JVM that wrote it, so the image's own launcher dumps it. It holds
 * the classes of [classList], recorded from an unobfuscated build by `recordCdsClassList`; for a
 * release image, [mapping], ProGuard's mapping, gives them their obfuscated names. Classes the list
 * misses load from the jars as usual. The JVM checks that the jars have the sizes they had then,
 * not their timestamps, and accepts the app's folder having moved, so an app installed elsewhere or
 * unpacked from the portable zip, whose entries carry a fixed time, still uses it.
 *
 * @param name the launcher's name, the package name.
 * @param os the host system, `mac`, `windows` or `linux`: the image runs only there.
 * @param workDir where the class list given to the JVM, its log and the like are written.
 */
class CdsArchive(
  private val name: String,
  private val os: String,
  private val classList: File,
  private val mapping: File?,
  private val workDir: File,
) {
  /** Writes the archive into the app image in [destinationDir], replacing any there. */
  fun dump(destinationDir: File) {
    val appDir = appDir(destinationDir)
    val archive = File(appDir, FILE_NAME)
    archive.delete()
    workDir.mkdirs()
    val entries = classList.readLines()
    val list = File(workDir, "app.classlist")
    list.writeText(
      (if (mapping == null) entries else obfuscated(entries, mapping)).joinToString("\n") + "\n",
    )
    val options = listOfNotNull(
      "-Xshare:dump",
      "-XX:SharedClassListFile=${list.absolutePath}",
      "-XX:SharedArchiveFile=${archive.absolutePath}",
      // The JVM's default address for the archive isn't free on recent macOS, so every launch would
      // move the archive and write to all of it; this one is.
      "-XX:SharedBaseAddress=0x7000000000".takeIf { os == "mac" },
    )
    val log = File(workDir, "dump.log")
    val exit = launch(appDir, options.joinToString(" "), log, inheritIo = false)
    if (exit != 0 || !archive.isFile) {
      throw GradleException("Dumping the class data sharing archive failed ($exit); see $log")
    }
    if (os == "mac") seal(appDir.parentFile.parentFile)
  }

  /**
   * Runs the app image in [destinationDir] with an empty profile in [home] until it is quit, and
   * writes the classes it loaded to [output].
   */
  fun record(destinationDir: File, home: File, output: File) {
    val appDir = appDir(destinationDir)
    workDir.mkdirs()
    val recorded = File(workDir, "recorded.classlist")
    recorded.delete()
    home.deleteRecursively()
    home.mkdirs()
    val options = "-Xshare:off -Duser.home=${home.absolutePath} " +
      "-XX:DumpLoadedClassList=${recorded.absolutePath}"
    launch(appDir, options, log = null, inheritIo = true) {
      // Where Windows and Linux keep the app's data, so it opens with the empty profile there too.
      it["APPDATA"] = File(home, "AppData/Roaming").absolutePath
      it["XDG_CONFIG_HOME"] = File(home, ".config").absolutePath
    }
    if (recorded.length() == 0L) throw GradleException("The app recorded no classes")
    recorded.copyTo(output, overwrite = true)
  }

  private fun appDir(destinationDir: File): File =
    destinationDir.walk().firstOrNull { it.isFile && it.name == "$name.cfg" }?.parentFile
      ?: throw GradleException("No app image with $name.cfg in $destinationDir")

  // The image's launcher, which reads JAVA_TOOL_OPTIONS like any JVM. It splits them at spaces.
  private fun launch(
    appDir: File,
    options: String,
    log: File?,
    inheritIo: Boolean,
    environment: (MutableMap<String, String>) -> Unit = {},
  ): Int {
    val launcher = when (os) {
      "mac" -> File(appDir.parentFile, "MacOS/$name")
      "windows" -> File(appDir.parentFile, "$name.exe")
      else -> File(appDir.parentFile.parentFile, "bin/$name")
    }
    if (!launcher.isFile) throw GradleException("No launcher at $launcher")
    if (listOf(workDir, appDir).any { dir -> dir.absolutePath.any(Char::isWhitespace) }) {
      throw GradleException("JAVA_TOOL_OPTIONS can't carry paths with spaces: $workDir, $appDir")
    }
    val builder = ProcessBuilder(launcher.path)
    if (inheritIo) builder.inheritIO() else builder.redirectErrorStream(true).redirectOutput(log)
    val env = builder.environment()
    env.keys.removeAll(setOf("_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"))
    env["JAVA_TOOL_OPTIONS"] = options
    environment(env)
    return builder.start().waitFor()
  }

  // Adding a file to a signed bundle breaks its seal, and macOS calls such an app damaged. jpackage
  // signs ad hoc, with the hardened runtime and entitlements, so the bundle is signed the same way
  // again. A bundle signed with an identity would need it, so the archive must come first there.
  private fun seal(bundle: File) {
    val (signed, details) = run("codesign", "-dv", bundle.path)
    if (!signed) return
    if ("Signature=adhoc" !in details) {
      throw GradleException("$bundle is signed with an identity; add $FILE_NAME before signing it")
    }
    val flags = Regex("""flags=\S*\(([^)]*)\)""").find(details)?.groupValues?.get(1).orEmpty()
    val entitlements = File(workDir, "entitlements.plist")
    entitlements.delete()
    run("codesign", "-d", "--entitlements", entitlements.path, "--xml", bundle.path)
    val command = buildList {
      addAll(listOf("codesign", "--force", "--sign", "-"))
      if ("runtime" in flags.split(',')) addAll(listOf("--options", "runtime"))
      if (entitlements.length() > 0) addAll(listOf("--entitlements", entitlements.path))
      add(bundle.path)
    }
    val (resigned, output) = run(*command.toTypedArray())
    val (valid, problem) = run("codesign", "--verify", "--strict", bundle.path)
    if (!resigned || !valid) throw GradleException("Couldn't sign $bundle again: $output$problem")
  }

  private fun run(vararg command: String): Pair<Boolean, String> {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    return (process.waitFor() == 0) to output
  }

  companion object {
    const val FILE_NAME = "ketch.jsa"

    private val JDK = listOf(
      "java/", "javax/", "jdk/", "sun/", "com/sun/", "com/apple/", "apple/", "org/w3c/", "org/xml/",
      "org/ietf/", "org/jcp/",
    )
    private val PRIMITIVES = mapOf(
      "void" to "V", "boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S", "int" to "I",
      "long" to "J", "float" to "F", "double" to "D",
    )
    // A method line: optional line numbers, return type, name(parameter types), new name.
    private val METHOD =
      Regex("""\s+(?:\d+:\d+:)?(\S+) ([^ (]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)""")
    private val CLASS_IN_DESCRIPTOR = Regex("""L([^;]+);""")

    /**
     * [entries], a class list of an unobfuscated build, with the names ProGuard gave the classes
     * and methods in [mapping]. Classes ProGuard removed are left out, and so are lambdas whose
     * classes or methods can't be named.
     */
    fun obfuscated(entries: List<String>, mapping: File): List<String> {
      val classes = HashMap<String, String>()
      val methods = HashMap<String, String>()
      var current = ""
      mapping.forEachLine { line ->
        when {
          line.isBlank() || line.startsWith("#") -> {}
          !line.startsWith(" ") -> {
            val (original, renamed) = line.removeSuffix(":").split(" -> ")
            current = original.replace('.', '/')
            classes[current] = renamed.replace('.', '/')
          }
          else -> METHOD.matchEntire(line)?.destructured?.let { (returns, method, args, renamed) ->
            // Methods named with their class were inlined from it.
            if ('.' !in method) {
              val parameters = args.split(',').filter { it.isNotEmpty() }
              val signature = parameters.joinToString("", "(", ")") { descriptor(it) }
              methods.putIfAbsent("$current.$method$signature${descriptor(returns)}", renamed)
            }
          }
        }
      }
      fun type(name: String): String? = if (JDK.any { name.startsWith(it) }) name else classes[name]
      fun types(descriptor: String): String? {
        var named = true
        val renamed = CLASS_IN_DESCRIPTOR.replace(descriptor) { match ->
          type(match.groupValues[1])?.let { "L$it;" } ?: match.value.also { named = false }
        }
        return renamed.takeIf { named }
      }
      return entries.mapNotNull { line ->
        when {
          line.startsWith("#") || line.startsWith("@lambda-form-invoker") -> line
          line.startsWith("@lambda-proxy ") -> {
            val parts = line.split(' ')
            if (parts.size != 10) return@mapNotNull null
            val (caller, invoked, invokedType, methodType) = parts.subList(1, 5)
            val (kind, implClass, implMethod, implType) = parts.subList(5, 9)
            // The interface method keeps its name unless ProGuard renamed its interface.
            val iface = invokedType.substringAfterLast(")L", "").removeSuffix(";")
            val ifaceRenamed = iface.isNotEmpty() && type(iface) != iface
            val method = if (JDK.any { implClass.startsWith(it) }) {
              implMethod
            } else {
              methods["$implClass.$implMethod$implType"]
            }
            val renamed = listOf(
              type(caller), invoked.takeUnless { ifaceRenamed }, types(invokedType),
              types(methodType), kind, type(implClass), method, types(implType), types(parts[9]),
            )
            if (renamed.all { it != null }) "@lambda-proxy " + renamed.joinToString(" ") else null
          }
          line.startsWith("@") -> null
          else -> type(line.substringBefore(' '))?.let { it + " " + line.substringAfter(' ') }
        }
      }
    }

    private fun descriptor(javaType: String): String {
      val base = javaType.replace("[]", "")
      val dimensions = (javaType.length - base.length) / 2
      return "[".repeat(dimensions) + (PRIMITIVES[base] ?: "L${base.replace('.', '/')};")
    }
  }
}

compose.desktop {
  application {
    mainClass = "com.linroid.ketch.app.desktop.MainKt"
    // Keep the initial heap small on high-memory desktops. Native graphics and JVM memory
    // sit outside this limit; downloads stream their contents rather than retaining files.
    jvmArgs += listOf("-Xms32m", "-Xmx512m")
    // The JVM prints its own warnings to stdout, before main runs. As the browser extension's
    // native messaging host (NativeMessagingHost), stdout carries length-prefixed messages, and a
    // warning there breaks the reply, so they go to stderr. These come after JAVA_TOOL_OPTIONS, so
    // diagnostic logging asked for there only reaches a file (`-Xlog:cds:file=cds.log`).
    jvmArgs += listOf("-Xlog:all=off:stdout", "-Xlog:all=warning:stderr")
    // The class data sharing archive (CdsArchive). JDK 21 maps it at a random address by default,
    // which moves every pointer in it and leaves little of it shared; mapped at the address it was
    // dumped for, as JDK 19 and earlier did, it stays shared, and is moved only when that is taken.
    jvmArgs += listOf(
      "-XX:SharedArchiveFile=\$APPDIR/${CdsArchive.FILE_NAME}",
      "-XX:+UnlockDiagnosticVMOptions",
      "-XX:ArchiveRelocationMode=0",
    )
    providers.gradleProperty("desktopJavaHome").orNull?.let { javaHome = it }

    buildTypes.release.proguard {
      obfuscate.set(true)
      configurationFiles.from(
        proguardRules,
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
        // The identifier earlier releases shipped with, so an update keeps its notification
        // permission and other per-app macOS state.
        bundleID = "com.linroid.ketch.app.desktop"
        // Lists Ketch as an app for magnet: links, and for ketch: pairing links; macOS delivers
        // them through Desktop.setOpenURIHandler (MagnetHandler.kt). CFBundleLocalizations names
        // the languages macOS may show the app in. The Compose plugin already sets
        // CFBundleAllowMixedLocalizations to true; repeating it here would duplicate the key.
        val localizations = macLocalizations.joinToString("\n") { "|    <string>$it</string>" }
        infoPlist {
          extraKeysRawXml = """
            |  <key>CFBundleLocalizations</key>
            |  <array>
            $localizations
            |  </array>
            |  <key>CFBundleURLTypes</key>
            |  <array>
            |    <dict>
            |      <key>CFBundleURLName</key>
            |      <string>Magnet link</string>
            |      <key>CFBundleURLSchemes</key>
            |      <array>
            |        <string>magnet</string>
            |      </array>
            |    </dict>
            |    <dict>
            |      <key>CFBundleURLName</key>
            |      <string>Ketch link</string>
            |      <key>CFBundleURLSchemes</key>
            |      <array>
            |        <string>ketch</string>
            |      </array>
            |    </dict>
            |  </array>
            |""".trimMargin()
        }
      }
      windows {
        iconFile.set(rootProject.file("art/icon.ico"))
        // Windows Installer upgrades the installed app only from a package with the same
        // UpgradeCode. Releases up to 0.0.1 carry the one jpackage derives from the vendor and the
        // name; pinned, so setting a vendor or renaming the package can't break upgrades.
        upgradeUuid = "C26C63A7-2024-313A-B051-DC34FFBA1BF7"
        // The plugin adds neither by default, which leaves the installed app nowhere to open
        // from but its folder in Program Files. The group names the Start menu folder, which
        // jpackage otherwise calls "Unknown".
        menuGroup = "Ketch"
        shortcut = true
      }
      linux {
        iconFile.set(rootProject.file("art/icon.png"))
      }
      packageVersion = installerVersion(providers.gradleProperty("VERSION_NAME").get())
    }
  }
}

// The tray menu is an AWT menu, which Windows draws with the fonts the runtime's fontconfig lists
// for the JVM's default charset. That is UTF-8 since JDK 18, and the JDK's Windows fontconfig only
// has UTF-8 sequences for Hindi, Japanese and Korean, so Chinese text showed as boxes. The runtime
// reads conf/fonts/fontconfig.properties ahead of its built-in table: the JDK's own template plus
// Chinese sequences, and a CJK fallback for other locales, where Ketch can still be in Chinese.
if (providers.systemProperty("os.name").get().startsWith("Windows")) {
  tasks.withType<AbstractJLinkTask>().configureEach {
    // The same fonts the JDK uses for the GBK, x-windows-950 and x-MS950-HKSCS charsets. Their
    // encoders, and Japanese and Korean ones, are in java.base on Windows, not in jdk.charsets.
    val simplified = "alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb"
    val traditional = "alphabetic,chinese-ms950,dingbats,symbol,chinese-ms950-extb"
    val hongKong = "alphabetic,chinese-ms950,chinese-hkscs,dingbats,symbol,chinese-ms950-extb"
    val sequences = mapOf(
      "UTF-8.zh.CN" to simplified,
      "UTF-8.zh.SG" to simplified,
      "UTF-8.zh.TW" to traditional,
      "UTF-8.zh.HK" to hongKong,
      "UTF-8.zh.MO" to hongKong,
      "UTF-8" to "alphabetic/default,chinese-ms936,japanese,korean,dingbats,symbol",
    ).entries.joinToString("\n", postfix = "\n") { (elc, fonts) ->
      "sequence.allfonts.$elc=$fonts"
    }
    doLast {
      val runtime = (this as AbstractJLinkTask).destinationDir.get().asFile
      val template = runtime.resolve("lib/fontconfig.properties.src")
      check(template.isFile) { "No fontconfig template in the runtime image: $template" }
      val config = runtime.resolve("conf/fonts/fontconfig.properties")
      config.parentFile.mkdirs()
      config.writeText(template.readText().trimEnd() + "\n\n# Added by Ketch\n" + sequences)
    }
  }
}

// Every app image gets its class data sharing archive, written as the image is created so that the
// packages and the portable app carry it.
val cdsClassList = layout.projectDirectory.file("cds/app.classlist")
val appName = checkNotNull(compose.desktop.application.nativeDistributions.packageName)
fun cdsArchive(task: String, mapping: File?) = CdsArchive(
  name = appName,
  os = hostOs.get(),
  classList = cdsClassList.asFile,
  mapping = mapping,
  workDir = layout.buildDirectory.dir("cds/$task").get().asFile,
)
tasks.withType<AbstractJPackageTask>().configureEach {
  val mapping = when (name) {
    "createDistributable" -> null
    "createReleaseDistributable" -> layout.buildDirectory.file(proguardMapping).get().asFile
    else -> return@configureEach
  }
  val archive = cdsArchive(name, mapping)
  inputs.file(cdsClassList).withPropertyName("cdsClassList")
  doLast { archive.dump((this as AbstractJPackageTask).destinationDir.get().asFile) }
}

// Records the classes the app loads into cds/app.classlist: it opens with an empty profile of its
// own, to be used as usual and quit. Record again when what the first window loads changes much,
// such as after a Compose update; classes the list misses still load, only more slowly.
tasks.register("recordCdsClassList") {
  group = "compose desktop"
  description = "Records the classes the desktop app loads, for its class data sharing archive."
  dependsOn("createDistributable")
  val image = tasks.named<AbstractJPackageTask>("createDistributable").flatMap { it.destinationDir }
  val home = layout.buildDirectory.dir("cds/profile")
  val output = cdsClassList.asFile
  val archive = cdsArchive(name, mapping = null)
  doLast { archive.record(image.get().asFile, home.get().asFile, output) }
}

// The portable Windows app: the release app folder with an empty data folder beside Ketch.exe,
// zipped. The data folder makes the app keep everything it writes there instead of in
// %APPDATA%\ketch (PortableApp.kt). The release workflow uploads it as
// ketch-desktop-<version>-windows-<arch>-portable.zip.
val portableDataFolder = tasks.register("portableDataFolder") {
  val outputDir = layout.buildDirectory.dir("portable/data-folder")
  outputs.dir(outputDir)
  doLast { outputDir.get().dir("data").asFile.mkdirs() }
}

tasks.register<Zip>("packageReleasePortableZip") {
  group = "compose desktop"
  description = "Packages the release app folder as the portable Windows app, a .zip."
  // Elsewhere the app folder is a macOS bundle or a Linux app, which the data folder doesn't
  // make portable; skipped there like packageReleaseMsi, without building the app first.
  val windows = providers.systemProperty("os.name").get().startsWith("Windows")
  enabled = windows
  if (windows) {
    // createReleaseDistributable writes the app to <packageName>\, here Ketch\.
    val appFolder = checkNotNull(compose.desktop.application.nativeDistributions.packageName)
    from(tasks.named("createReleaseDistributable"))
    from(portableDataFolder) { into(appFolder) }
  }
  destinationDirectory = layout.buildDirectory.dir("compose/binaries/main-release/portable")
  archiveFileName = "Ketch-portable.zip"
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

/**
 * The version the installers carry for [version], such as `0.0.1-rc15`. jpackage wants a MAJOR
 * above 0 on macOS, and Windows Installer only upgrades to a higher MAJOR.MINOR.BUILD (MAJOR and
 * MINOR at most 255, BUILD at most 65535), which the in-app updater relies on. So the release
 * MAJOR.MINOR.PATCH-rcN becomes (MAJOR + 1).MINOR.(PATCH * 1000 + N), with 999 for the final
 * release and 0 for any other build: 0.0.1-rc15 is 1.0.1015 and 0.0.1 is 1.0.1999. Releases up
 * to 0.0.1 all carried 1.0.0, which every later one upgrades.
 */
fun installerVersion(version: String): String {
  val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-(.+))?""").matchEntire(version)
    ?: throw GradleException("VERSION_NAME '$version' is not MAJOR.MINOR.PATCH[-PRERELEASE]")
  val (major, minor, patch, preRelease) = match.destructured
  val final = 999
  val stage = if (preRelease.isEmpty()) {
    final
  } else {
    Regex("""rc(\d+)""").matchEntire(preRelease)?.groupValues?.get(1)?.toInt() ?: 0
  }
  val build = patch.toInt() * 1000 + stage
  val fits = major.toInt() + 1 <= 255 && minor.toInt() <= 255 && build <= 65535 &&
    (preRelease.isEmpty() || stage < final)
  if (!fits) throw GradleException("VERSION_NAME '$version' doesn't fit an installer version")
  return "${major.toInt() + 1}.${minor.toInt()}.$build"
}
