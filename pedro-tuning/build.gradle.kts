plugins {
    id("com.android.library")
    kotlin("android")
}

description = "Gum's Pedro Pathing AutoTune setup, with the tuning procedures from Pedro's Quickstart."

android {
    namespace = "com.anshulnoori.gum.pedro.tuning"
    compileSdk = 30

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
}

dependencies {
    api(project(":pedro"))
    // Pedro's AutoTune. Its web server starts with the Robot Controller app, so only tuning
    // builds include this module; see docs/pedro.md.
    api("com.pedropathing:tuning:1.0.3")
    compileOnly("org.firstinspires.ftc:RobotCore:12.0.0")
    compileOnly("org.firstinspires.ftc:Hardware:12.0.0")
    compileOnly("org.firstinspires.ftc:FtcCommon:12.0.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("org.firstinspires.ftc:RobotCore:12.0.0")
    testImplementation("org.firstinspires.ftc:Hardware:12.0.0")
}

// The procedures copied from Pedro's Quickstart keep FIRST's BSD license, whose notice must
// travel with every source and binary copy; see NOTICE.md.
extensions.configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        pom.licenses {
            license {
                name.set("BSD-3-Clause-Clear (procedures copied from Pedro Pathing's Quickstart)")
                url.set("https://github.com/Pedro-Pathing/Quickstart/blob/4f33ef5/LICENSE")
                distribution.set("repo")
            }
        }
    }
}
tasks.matching { it.name == "bundleReleaseAar" || it.name == "sourceReleaseJar" }.configureEach {
    (this as AbstractArchiveTask).from("NOTICE.md") { into("META-INF/gum-pedro-tuning") }
}
