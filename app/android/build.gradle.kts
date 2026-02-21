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
    versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER")
      .orElse("1").get().toInt()
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
  }

  packaging {
    resources {
      excludes += setOf(
        "META-INF/{INDEX.LIST,io.netty.versions.properties}",
        "META-INF/*.version",
        "META-INF/native-image/**",
        "META-INF/version-control-info.textproto",
        "META-INF/com/android/build/gradle/app-metadata.properties",
        "META-INF/androidx/**",
        "kotlin/**",
        "DebugProbesKt.bin",
        "org/fusesource/**",
        "**/*.properties",
      )
    }
  }
}

dependencies {
  implementation(projects.app.shared)
  implementation(projects.library.core)
  implementation(projects.library.sqlite)
  implementation(projects.library.server)
  implementation(libs.androidx.activity.compose)
  debugImplementation(libs.compose.uiTooling)
}
