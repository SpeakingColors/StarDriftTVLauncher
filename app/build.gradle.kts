plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tvlauncher.home"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.tvlauncher.home"
        minSdk = 26
        targetSdk = 34
        versionCode = 68
        versionName = "0.9.45"
        // The repository of your fork, given by GitHub Actions (empty on other machines: the Update button stays hidden)
        buildConfigField("String", "UPDATE_REPO", "\"" + (System.getenv("GITHUB_REPOSITORY") ?: "") + "\"")
    }
    buildFeatures { buildConfig = true }

    // Fixed key so every build can update the previous install.
    // CI gets the key from GitHub secrets (KEYSTORE_FILE, KEYSTORE_PASSWORD).
    // The key comes from GitHub secrets (KEYSTORE_FILE, KEYSTORE_PASSWORD); it is not in the repo.
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "tvlauncher"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
