plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "nz.cassey.firetv"
    compileSdk = 34

    defaultConfig {
        applicationId = "nz.cassey.firetv"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.$runNumber"
    }

    // Fixed signing key so every new version installs over the old one
    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "firetv123"
            keyAlias = "firetv"
            keyPassword = "firetv123"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,LICENSE*,NOTICE*}"
        }
    }
}

dependencies {
    implementation("dev.mobile:dadb:1.2.10")
    implementation("androidx.core:core:1.13.1")
}
