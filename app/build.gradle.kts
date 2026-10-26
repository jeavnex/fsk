plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Номер версии передаёт сборка на GitHub (номер запуска). Локально — 1.
val vc = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val keystorePath: String? = System.getenv("KINO_KEYSTORE")

android {
    namespace = "net.tenyls.kinotv"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.tenyls.kinotv"
        minSdk = 24
        targetSdk = 34
        versionCode = vc
        versionName = "1.$vc"
        buildConfigField("String", "REPO", "\"jeavnex/fsk\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KINO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KINO_KEY_ALIAS") ?: "kino"
                keyPassword = System.getenv("KINO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
