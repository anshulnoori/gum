# Publishing

Gum's library modules publish to a Maven repository with Gradle's `maven-publish` plugin. The root `build.gradle.kts` configures every module.

## Coordinates

| Module | Artifact | Packaging |
| --- | --- | --- |
| `:core` | `com.anshulnoori.gum:core` | jar |
| `:hardware` | `com.anshulnoori.gum:hardware` | jar |
| `:ftc` | `com.anshulnoori.gum:ftc` | aar |
| `:pedro` | `com.anshulnoori.gum:pedro` | aar |
| `:pedro-tuning` | `com.anshulnoori.gum:pedro-tuning` | aar |

Each artifact comes with a sources jar, a POM, and Gradle module metadata. The POM names the MIT license, the GitHub repository as SCM, and the developer. Modules depend on each other by these coordinates, so a consumer only declares the modules it uses directly.

`pedro-tuning` also contains procedures copied from Pedro Pathing's Quickstart, which keep FIRST's BSD license. Its POM lists that license next to MIT, and its AAR and sources jar carry [`NOTICE.md`](../pedro-tuning/NOTICE.md) at `META-INF/gum-pedro-tuning/NOTICE.md`. An app that ships `pedro-tuning` must reproduce that notice with the app, as the license requires.

The Gradle plugin in `gradle-plugin/` is a separate build and does not publish yet.

## Versions

The `version` in the root `build.gradle.kts` applies to every module, and follows semantic versioning. Between releases it ends in `-SNAPSHOT`; Gradle publishes each snapshot with a timestamp, and consumers get the newest one.

To release:

1. Set `version` to the release, such as `0.1.0`, and commit.
2. Publish, then tag the commit `v0.1.0`.
3. Set `version` to the next snapshot, such as `0.2.0-SNAPSHOT`, and commit.

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
```

Without `gumMavenUrl`, the `gum` repository does not exist and only `publishToMavenLocal` works. A `file://` URL publishes to a local directory without credentials, which shows exactly what a release will contain.

## Consuming

An FTC app adds the repository and depends on the modules it uses. In `TeamCode/build.gradle`:

```groovy
repositories {
    maven {
        url = 'https://maven.example.com/releases'
        content { includeGroup 'com.anshulnoori.gum' }
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
    implementation 'com.anshulnoori.gum:pedro:0.1.0'
}
```

The app still needs the FTC SDK, which Gum compiles against but does not bring.
