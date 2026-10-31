plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.flashcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.flashcam"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7"
    }

    signingConfigs {
        create("shared") {
            storeFile = file("flashcam.keystore")
            storePassword = "REMOVED"
            keyAlias = "flashcam"
            keyPassword = "REMOVED"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // On-device person segmentation, used in place of LiDAR depth
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
}
