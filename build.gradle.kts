plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("android") version "2.3.20" apply false
    id("com.android.library") version "8.13.2" apply false
}

subprojects {
    group = "com.anshulnoori.gum"
    version = "0.1.0-SNAPSHOT"

    // Each module publishes as com.anshulnoori.gum:<module name>, with a sources jar; see docs/publishing.md.
    apply(plugin = "maven-publish")
    val publishing = extensions.getByType<PublishingExtension>()

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> { withSourcesJar() }
        publishing.publications.register<MavenPublication>("maven") { from(components["java"]) }
    }
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            publishing { singleVariant("release") { withSourcesJar() } }
        }
        // AGP creates the release component after the module's build script runs.
        afterEvaluate {
            publishing.publications.register<MavenPublication>("maven") { from(components["release"]) }
        }
    }

    publishing.publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Gum ${project.name}")
            description.set(provider { project.description })
            url.set("https://github.com/anshulnoori/gum")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("anshulnoori")
                    name.set("Anshul Noori")
                    url.set("https://github.com/anshulnoori")
                }
            }
            scm {
                url.set("https://github.com/anshulnoori/gum")
                connection.set("scm:git:https://github.com/anshulnoori/gum.git")
                developerConnection.set("scm:git:ssh://git@github.com/anshulnoori/gum.git")
            }
        }
    }

    // The repository's URL and credentials come from Gradle properties or environment variables,
    // never from this repository: gumMavenUrl, gumUsername, and gumPassword.
    providers.gradleProperty("gumMavenUrl").orNull?.let { repositoryUrl ->
        publishing.repositories.maven {
            name = "gum"
            url = uri(repositoryUrl)
            if (url.scheme != "file") credentials(PasswordCredentials::class)
        }
    }
}
