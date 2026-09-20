import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "offlineshare"

include(":protocol")

// The :app module needs the Android SDK. Headless CI boxes and plain JDK
// checkouts often do not have one, and configuring the Android Gradle Plugin
// without an SDK fails the whole build -- including `:protocol:test`, which
// has nothing to do with Android. So the app module is only wired in when an
// SDK is actually resolvable.
// Blank counts as absent: GitHub's ubuntu runners ship an Android SDK and
// export ANDROID_HOME, so a job that wants the SDK-less path has to clear the
// variable, and an empty string must not read as "here is an SDK".
fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

val androidSdkDir: String? =
    System.getenv("ANDROID_HOME").orNullIfBlank()
        ?: System.getenv("ANDROID_SDK_ROOT").orNullIfBlank()
        ?: file("local.properties")
            .takeIf { it.isFile }
            ?.let { propsFile ->
                Properties().apply { propsFile.inputStream().use(::load) }.getProperty("sdk.dir")
            }.orNullIfBlank()

if (androidSdkDir != null && file(androidSdkDir).isDirectory) {
    include(":app")
} else {
    logger.lifecycle(
        "No Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties#sdk.dir). " +
            "Skipping :app; :protocol still builds and tests."
    )
}
