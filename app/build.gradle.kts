plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mkaafi6.muufi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mkaafi6.muufi"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        // Tiny app: one ABI only (all phones since ~2017).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // Release is signed with the auto-generated debug key so the APK is
        // installable (sideload) without managing a keystore. Fine for a
        // personal app; swap in a real keystore later if desired.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
