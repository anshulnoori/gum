plugins {
    id("com.android.library")
    kotlin("android")
}

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
