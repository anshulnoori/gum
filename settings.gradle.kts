pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Sloth and its Sinister runtime.
        maven("https://repo.dairy.foundation/releases") {
            content { includeGroupByRegex("dev\\.frozenmilk.*") }
        }
        // Panels.
        maven("https://mymaven.bylazar.com/releases") {
            content { includeGroupByRegex("com\\.bylazar.*") }
        }
    }
}

rootProject.name = "gum"
include(":core")
include(":ftc")
include(":hardware")
