plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.anshulnoori.gum.pedro"
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
    api(project(":ftc"))
    // Pedro Pathing 3 and Ivy's commands for it, from the same authors as Ivy.
    api("com.pedropathing:core:3.0.2")
    api("com.pedropathing.ivy:pedro:1.1.1")
    compileOnly("org.firstinspires.ftc:RobotCore:12.0.0")
    compileOnly("org.firstinspires.ftc:Hardware:12.0.0")
    compileOnly("org.firstinspires.ftc:FtcCommon:12.0.0")

    testImplementation(kotlin("test-junit"))
}
