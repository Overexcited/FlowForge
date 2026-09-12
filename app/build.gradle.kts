plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flowforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flowforge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "0.2.1"
    }

    signingConfigs {
        create("testRelease") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "androiddebugkey"
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("testRelease")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures { buildConfig = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin { jvmToolchain(17) }
