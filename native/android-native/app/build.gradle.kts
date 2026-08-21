plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phoneinputenhanced.nativeclient"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phoneinputenhanced.nativeclient"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.4.1-preview.1-readback-stability"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
