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
        versionCode = 20
        versionName = "0.20"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
