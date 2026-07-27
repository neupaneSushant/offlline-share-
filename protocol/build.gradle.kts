import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // Emit 11 bytecode so the Android app module can consume this as-is, with
    // no desugaring surprises on older API levels. Deliberately not pinned to
    // a toolchain: this module has no native bits and no JDK-version-specific
    // behaviour, so requiring a specific JDK only breaks builds on machines
    // that have a perfectly usable different one.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The throughput tests move hundreds of megabytes through loopback.
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
