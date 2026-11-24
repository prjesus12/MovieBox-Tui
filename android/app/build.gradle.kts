plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.moviebox.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.moviebox.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.30"
        ndk {
            // Must match the ABIs android/scripts/build-rust.sh produced.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Local sideload builds: sign with the debug key so the APK installs.
            signingConfig = signingConfigs.getByName("debug")
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
        jniLibs {
            // The Rust library is already stripped by the release profile.
            keepDebugSymbols += "**/libmoviebox_tui.so"
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation("${libs.jna.get()}@aar")
    implementation(libs.coroutines.android)
}
