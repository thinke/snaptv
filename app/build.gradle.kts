plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.thinke.snaptv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.thinke.snaptv"
        minSdk = 26
        targetSdk = 36
        // CI passes these from the release tag (v1.2.3 -> 1.2.3); local builds use the defaults.
        versionCode = System.getenv("SNAPTV_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("SNAPTV_VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // FFmpeg ships native code per CPU type; 32-bit x86 TV boxes practically don't exist.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Release signing comes from the environment so no key material lives in the repo.
    val keystore = System.getenv("SNAPTV_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SNAPTV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SNAPTV_KEY_ALIAS")
                keyPassword = System.getenv("SNAPTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release code (minified, not debuggable) under its own ID, with just the visualizer
        // benchmark (src/bench): installs next to the real app to time styles on a device.
        create("bench") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench"
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The visualizer is shared with the desktop app (same Compose drawing API on both).
    sourceSets.getByName("main").kotlin.srcDir("../shared/visuals")

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)
    // FFmpeg audio decoders (GPL-3.0, like SnapTV), selectable per codec in Settings → Decoders.
    implementation(libs.jellyfin.media3.ffmpeg)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
