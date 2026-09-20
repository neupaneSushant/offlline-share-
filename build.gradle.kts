// Every Kotlin plugin is declared here, with a version, and applied `false`.
//
// They have to be declared together. `kotlin.jvm` and `kotlin.android` are
// different plugin ids backed by the same kotlin-gradle-plugin artifact, so
// declaring one here and requesting the other from a subproject *with a
// version* makes Gradle refuse: the jar is already on the classpath and it
// cannot check that the requested version matches. Resolving both here puts
// one consistent Kotlin plugin on the shared classpath, and subprojects then
// apply them by id with no version at all.
//
// The Android Gradle Plugin is deliberately not here. It resolves only from
// google(), and a checkout with no Android SDK -- where settings.gradle.kts
// has already dropped :app -- would still have to download it just to
// configure the root project. It carries its version in :app instead, which
// is the only place it is ever applied.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
