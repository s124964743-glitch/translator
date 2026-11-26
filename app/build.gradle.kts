plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kala.keyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kala.keyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("kala") {
            storeFile = rootProject.file("kala.jks")
            storePassword = "kalakeyboard"
            keyAlias = "kala"
            keyPassword = "kalakeyboard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kala")
        }
        debug {
            signingConfig = signingConfigs.getByName("kala")
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
        abortOnError = false
        checkReleaseBuilds = false
    }
}
