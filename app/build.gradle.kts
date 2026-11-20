import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.adblocker.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adblocker.app"
        minSdk = 21
        targetSdk = 34
        val buildNumber = (project.findProperty("buildNumber") as? String)?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    // Sign every debug build with ONE deterministic key. CI caches ~/.android,
    // so all releases share the same signature and in-app updates install
    // without the dreaded "signature mismatch" / uninstall step.
    signingConfigs {
        getByName("debug") {
            val keyStore = File(System.getProperty("user.home"), ".android/debug.keystore")
            if (keyStore.exists()) {
                storeFile = keyStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}