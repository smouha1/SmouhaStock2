plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun envOrDefault(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

android {
    namespace = "com.smouha.stock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smouha.stock"
        minSdk = 21
        targetSdk = 34
        versionCode = 120
        versionName = "5.5.0"
    }

    signingConfigs {
        create("release") {
            storeFile     = file(envOrDefault("SMOUHA_KEYSTORE_FILE", "/tmp/smouha-keys/smouha-release.jks"))
            storePassword = envOrDefault("SMOUHA_KEYSTORE_PASS", "SmouhaOps2026#Key")
            keyAlias      = envOrDefault("SMOUHA_KEY_ALIAS", "smouha_release_key")
            keyPassword   = envOrDefault("SMOUHA_KEY_PASS", "SmouhaOps2026#Key")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
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
