plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun envOrBlank(name: String): String =
    System.getenv(name)?.trim() ?: ""

android {
    namespace = "com.smouha.stock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smouha.stock"
        minSdk = 21
        targetSdk = 34
        versionCode = 180
        versionName = "5.11.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = envOrBlank("SMOUHA_KEYSTORE_FILE")
            if (ksPath.isNotEmpty()) {
                storeFile = file(ksPath)
            }
            storePassword = envOrBlank("SMOUHA_KEYSTORE_PASS")
            keyAlias      = envOrBlank("SMOUHA_KEY_ALIAS")
            keyPassword   = envOrBlank("SMOUHA_KEY_PASS")
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
