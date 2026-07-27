// Android plugins are declared in :app rather than here on purpose. Listing
// them at the root makes Gradle resolve the Android Gradle Plugin even on a
// machine with no Android SDK, where settings.gradle.kts has already excluded
// :app -- which would fail the build for anyone who only wants :protocol.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
