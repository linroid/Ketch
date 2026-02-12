rootProject.name = "KDown"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Library modules
include(":library:core")
include(":library:api")
include(":library:remote")
include(":library:endpoints")
include(":library:ktor")
include(":library:kermit")
include(":library:sqlite")
include(":server")

// App modules
include(":app:shared")
include(":app:android")
include(":app:desktop")
include(":app:web")
include(":cli")
