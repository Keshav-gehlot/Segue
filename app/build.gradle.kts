// AGP 9 compiles Kotlin itself (built-in Kotlin), so only the Compose compiler plugin is applied here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.segue.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.segue.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.2"
        // Redirect URI of the Spotify developer app, segue://callback: the Spotify Auth library's
        // LoginActivity receives it.
        manifestPlaceholders["redirectSchemeName"] = "segue"
        manifestPlaceholders["redirectHostName"] = "callback"
    }

    // A fixed debug key, committed on purpose: every build (local or CI) is signed alike, so a newer
    // APK installs over an older one without uninstalling, which would wipe the pairing and the logs.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":engine"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.play.services.nearby)
    // Spotify App Remote SDK: distributed as a file only (see app/libs/README.md). It needs Gson at run time.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.gson)
    implementation(files("libs/spotify-auth-release-2.1.0.aar"))
    implementation(libs.androidx.browser)
}
