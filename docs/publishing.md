# Publishing

Gum's library modules and its Gradle plugin publish to a Maven repository with Gradle's `maven-publish` plugin. The root `build.gradle.kts` configures every library module; `gradle-plugin/build.gradle.kts` configures the plugin.

## Coordinates

| Module | Artifact | Packaging |
| --- | --- | --- |
| `:core` | `com.anshulnoori.gum:core` | jar |
| `:hardware` | `com.anshulnoori.gum:hardware` | jar |
| `:ftc` | `com.anshulnoori.gum:ftc` | aar |
| `gradle-plugin/` | `com.anshulnoori.gum:gum-gradle-plugin`, plugin ID `com.anshulnoori.gum.hardware` | jar |

Each artifact comes with a sources jar, a POM, and Gradle module metadata. The POM names the MIT license, the GitHub repository as SCM, and the developer. Modules depend on each other by these coordinates, so a consumer only declares the modules it uses directly.

The Gradle plugin also publishes a plugin marker, `com.anshulnoori.gum.hardware:com.anshulnoori.gum.hardware.gradle.plugin`, which lets Gradle find the plugin by its ID. Its group is the plugin ID, so a repository filter must allow `com.anshulnoori.gum` and the groups under it.

## Versions

The `version` in the root `build.gradle.kts` applies to every library module, and `gradle-plugin/build.gradle.kts` must set the same version. The plugin writes its version into its jar and resolves `com.anshulnoori.gum:hardware` at that version, and compiles the hardware sources with the Kotlin version of the app's Kotlin plugin, so neither is set by hand. Versions follow semantic versioning. Between releases it ends in `-SNAPSHOT`; Gradle publishes each snapshot with a timestamp, and consumers get the newest one.

To release:

1. Set `version` in both files to the release, such as `0.1.0`, and commit.
2. Publish, then tag the commit `v0.1.0`.
3. Set both to the next snapshot, such as `0.2.0-SNAPSHOT`, and commit.

## Publishing

The repository's URL and credentials stay out of this repository. Set them as Gradle properties in `~/.gradle/gradle.properties`:

```properties
gumMavenUrl=https://maven.example.com/releases
gumUsername=...
gumPassword=...
```

or as environment variables: `ORG_GRADLE_PROJECT_gumMavenUrl`, `ORG_GRADLE_PROJECT_gumUsername`, and `ORG_GRADLE_PROJECT_gumPassword`. Then run, with JDK 17 or newer and an Android SDK:

```sh
./gradlew publishAllPublicationsToGumRepository
./gradlew -p gradle-plugin publishAllPublicationsToGumRepository
```

Without `gumMavenUrl`, the `gum` repository does not exist and only `publishToMavenLocal` works. A `file://` URL publishes to a local directory without credentials, which shows exactly what a release will contain.

## Consuming

An FTC app finds the plugin through `pluginManagement` in `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven {
            url = 'https://maven.example.com/releases'
            content { includeGroupByRegex 'com\\.anshulnoori\\.gum.*' }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

and declares it with a version in the root `build.gradle`:

```groovy
plugins {
    id 'com.anshulnoori.gum.hardware' version '0.1.0' apply false
}
```

It adds the same repository for the libraries and depends on the modules it uses. In `TeamCode/build.gradle`:

```groovy
repositories {
    maven {
        url = 'https://maven.example.com/releases'
        content { includeGroupByRegex 'com\\.anshulnoori\\.gum.*' }
    }
    // Gum's dependencies Sloth and Panels come from their own repositories.
    maven {
        url = 'https://repo.dairy.foundation/releases'
        content { includeGroupByRegex 'dev\\.frozenmilk.*' }
    }
    maven {
        url = 'https://mymaven.bylazar.com/releases'
        content { includeGroupByRegex 'com\\.bylazar.*' }
    }
}

dependencies {
    implementation 'com.anshulnoori.gum:ftc:0.1.0'
}
```

The app still needs the FTC SDK, which Gum compiles against but does not bring.
