plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonecam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phonecam"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.0.2"
    }

    signingConfigs {
        // Releases are signed with a keystore from the environment (CI). Without it the APK is debug-signed.
        val ks = System.getenv("PHONECAM_KEYSTORE")
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("PHONECAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PHONECAM_KEY_ALIAS")
                keyPassword = System.getenv("PHONECAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
}
