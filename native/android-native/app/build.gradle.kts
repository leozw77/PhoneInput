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
        versionCode = 16
        versionName = "1.4.1-otp-preview.3"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".otpPreview2"
        }
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
