plugins {
    kotlin("jvm")
}

// KStateMachine's DSL inlines Java 17 bytecode. Android's D8 desugars it for the Control Hub.
kotlin {
    jvmToolchain(17)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Ivy owns commands and reservations. Its Scheduler is a JVM-wide singleton that sessions drive.
    api("com.pedropathing.ivy:core:1.1.1")
    // Subsystem builds its state machines with KStateMachine; TeamCode never uses it directly.
    implementation("io.github.nsk90:kstatemachine:0.38.1")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
