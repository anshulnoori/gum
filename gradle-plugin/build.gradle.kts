plugins {
    `java-gradle-plugin`
}

group = "com.anshulnoori.gum"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.13.2")
}

gradlePlugin {
    plugins {
        register("hardware") {
            id = "com.anshulnoori.gum.hardware"
            implementationClass = "com.anshulnoori.gum.gradle.GumHardwarePlugin"
        }
    }
}
