import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // All three carry versions and resolve together onto this module's
    // classpath. That co-location is required: the Kotlin Android plugin
    // looks up AGP's own classes, so the two must share a classloader.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.offshare.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.offshare.app"

        // Android 10. Both APIs this app is built around landed here:
        // WifiP2pConfig.Builder (which is what lets us ask for the 5 GHz band)
        // and WifiNetworkSpecifier (which lets the guest join the hotspot
        // without a trip to Settings). Below 29 neither exists, so the app
        // could not deliver either half of what it promises.
        minSdk = 29
        targetSdk = 35

        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// The Kotlin Android plugin contributes a top-level `kotlin` extension. There
// is no `kotlin` block inside `android {}` -- putting one there fails to
// resolve at configuration time.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
}
