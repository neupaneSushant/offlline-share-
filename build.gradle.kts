// Intentionally empty.
//
// Plugins are declared per module, each with its version, and this file
// declares none. Two constraints force that, and they pull opposite ways:
//
//  1. The Kotlin Android plugin reaches into AGP's classes (it looks up
//     com.android.build.gradle.api.BaseVariant). The two must therefore land
//     on the SAME classloader. Declaring Kotlin here and AGP in :app puts
//     them on different ones, and applying the Kotlin plugin then dies with
//     ClassNotFoundException: BaseVariant.
//
//  2. AGP resolves only from google(), so it cannot be declared here either:
//     a JDK-only checkout, where settings.gradle.kts has already dropped
//     :app, would still have to download AGP just to configure the root.
//
// Declaring nothing here satisfies both. :app resolves AGP, kotlin-android
// and the compose plugin together onto one classpath, :protocol resolves
// kotlin-jvm onto its own, and the two never have to agree about anything.
//
// It also avoids a third trap: kotlin.jvm and kotlin.android are separate
// plugin ids backed by the same kotlin-gradle-plugin artifact. Declaring one
// at the root and requesting the other from a subproject with a version makes
// Gradle refuse -- the jar is already on the classpath, so it cannot check
// that the requested version matches.
