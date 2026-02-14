apply(plugin = "maven-publish")

group = "com.linroid.kdown"
version = providers.gradleProperty("kdown.version").get()

configure<PublishingExtension> {
  publications.withType<MavenPublication> {
    pom {
      name.set("KDown ${project.name.replaceFirstChar { it.uppercase() }}")
      description.set(
        "KDown - Kotlin Multiplatform download manager library"
      )
      url.set("https://github.com/linroid/KDown")
      inceptionYear.set("2025")
      licenses {
        license {
          name.set("The Apache License, Version 2.0")
          url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
          distribution.set(
            "https://www.apache.org/licenses/LICENSE-2.0.txt"
          )
        }
      }
      developers {
        developer {
          id.set("linroid")
          name.set("linroid")
          url.set("https://github.com/linroid")
        }
      }
      scm {
        url.set("https://github.com/linroid/KDown")
        connection.set("scm:git:git://github.com/linroid/KDown.git")
        developerConnection.set(
          "scm:git:ssh://git@github.com/linroid/KDown.git"
        )
      }
    }
  }
}

val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
if (signingKey != null) {
  apply(plugin = "signing")
  configure<SigningExtension> {
    val signingKeyId =
      providers.environmentVariable("SIGNING_KEY_ID").orNull
    val signingPassword =
      providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    sign(extensions.getByType<PublishingExtension>().publications)
  }
}
