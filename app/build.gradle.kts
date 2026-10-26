plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Номер версии передаёт сборка на GitHub (номер запуска). Локально — 1.
val vc = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

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

    // Постоянный ключ подписи берётся из секретов репозитория (FSK_KEYSTORE_BASE64 / FSK_KEYSTORE_PASSWORD):
    // тогда каждая новая версия ставится поверх старой, вход и настройки сохраняются.
    val ksPath: String? = System.getenv("FSK_KEYSTORE")
    signingConfigs {
        create("release") {
            if (ksPath != null) {
                storeFile = file(ksPath)
                storeType = "pkcs12"
                storePassword = System.getenv("FSK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FSK_KEY_ALIAS")
                keyPassword = System.getenv("FSK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
