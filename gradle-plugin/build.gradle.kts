plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.anshulnoori.gum"
// Keep in step with the library version in ../build.gradle.kts.
version = "0.1.0-SNAPSHOT"
description = "Gum's Gradle plugin, which generates an FTC robot configuration from Kotlin."

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.13.2")
}

gradlePlugin {
    plugins {
        register("hardware") {
            id = "com.anshulnoori.gum.hardware"
            implementationClass = "com.anshulnoori.gum.gradle.GumHardwarePlugin"
            displayName = "Gum hardware"
            description = project.description
        }
    }
}

// java-gradle-plugin publishes the plugin as com.anshulnoori.gum:gum-gradle-plugin and a marker that
// resolves the plugin ID; see ../docs/publishing.md. The POM matches the library modules'.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Gum ${project.name}")
            description.set(project.description)
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

    // The same gumMavenUrl, gumUsername, and gumPassword properties as the library modules.
    providers.gradleProperty("gumMavenUrl").orNull?.let { repositoryUrl ->
        repositories.maven {
            name = "gum"
            url = uri(repositoryUrl)
            if (url.scheme != "file") credentials(PasswordCredentials::class)
        }
    }
}
