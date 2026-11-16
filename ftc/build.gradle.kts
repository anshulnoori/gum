plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.anshulnoori.gum.ftc"
    // Matches the FTC SDK v12.0 template.
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
    api(project(":core"))
    api(project(":hardware"))
    // Sloth registers programs at app start and after each hot reload.
    api("dev.frozenmilk.sinister:Sloth:0.3.2")
    // Panels: telemetry, field drawing, and live-tuned values in the browser.
    api("com.bylazar:fullpanels:1.0.20")
    // The consuming FTC app supplies the SDK.
    compileOnly("org.firstinspires.ftc:RobotCore:12.0.0")
    compileOnly("org.firstinspires.ftc:Hardware:12.0.0")
    compileOnly("org.firstinspires.ftc:FtcCommon:12.0.0")

    testImplementation(kotlin("test-junit"))
    // Telemetry, for the Panels test.
    testImplementation("org.firstinspires.ftc:RobotCore:12.0.0")
}
