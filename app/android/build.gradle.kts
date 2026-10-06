import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.composeCompiler)
}


kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
  }
}

android {
  namespace = "com.linroid.ketch.app.android"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "com.linroid.ketch.app"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionName = providers.gradleProperty("VERSION_NAME").get()
    // A property rather than GITHUB_RUN_NUMBER, which changes on every CI run and so would
    // keep any build from reusing its configuration cache. The release workflow passes it.
    versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
  }

  flavorDimensions += "distribution"
  productFlavors {
    create("direct") { dimension = "distribution" }
    create("play") { dimension = "distribution" }
  }

  signingConfigs {
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    if (keystoreFile != null) {
      create("release") {
        storeFile = file(keystoreFile)
        storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("ANDROID_KEY_ALIAS")
        keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        rootDir.resolve("app/proguard-rules.pro"),
      )
      signingConfigs.findByName("release")?.let {
        signingConfig = it
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  // Android 13+ lists the languages of res/values-* in Ketch's language setting. Each translation
  // adds the app's own strings there, beside the shared UI's Compose resources; English, the
  // unqualified values, is named in res/resources.properties.
  androidResources {
    generateLocaleConfig = true
  }

  packaging {
    resources {
      // kotlin/**/*.kotlin_builtins are read only by kotlin-reflect, which the app leaves out.
      excludes += setOf(
        "META-INF/DEPENDENCIES",
        "META-INF/{INDEX.LIST,io.netty.versions.properties}",
        "META-INF/*.version",
        "META-INF/native-image/**",
        "META-INF/version-control-info.textproto",
        "META-INF/com/android/build/gradle/app-metadata.properties",
        "META-INF/androidx/**",
        "META-INF/**/*.properties",
        "kotlin/**",
        "kotlinx-schema.properties",
        "DebugProbesKt.bin",
        "org/fusesource/**",
      )
    }
  }
}

// Koog, kotlinx-schema and Ktor's server depend on kotlin-reflect for features the app does not
// use (reflective tool sets and schemas, loading server modules by name). Its R8 rules keep most
// of it, so release builds leave it out.
configurations.matching { it.name.endsWith("ReleaseRuntimeClasspath") }.configureEach {
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

dependencies {
  "directImplementation"(projects.updater)
  testImplementation(libs.kotlin.testJunit)
  testImplementation(libs.kotlinx.coroutines.test)
  implementation(projects.config)
  implementation(projects.app.shared)
  implementation(projects.ai.discover)
  implementation(projects.library.core)
  implementation(projects.library.ktor)
  implementation(projects.library.ftp)
  implementation(projects.library.torrent)
  implementation(projects.library.sqlite)
  implementation(projects.library.server)
  implementation(libs.dnssd)
  implementation(libs.androidx.activity.compose)
  debugImplementation(libs.compose.uiTooling)
}
