import java.util.Properties

// Shares the app's signing credentials so both halves come from the same keystore. That is not
// required for this to work — they are separate packages — but it keeps one key to look after.
val signingProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketssh.recovery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pocketssh.recovery"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    val releaseSigning = signingProps.getProperty("releaseStoreFile")?.let { path ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(path)
            storePassword = signingProps.getProperty("releaseStorePassword")
            keyAlias = signingProps.getProperty("releaseKeyAlias")
            keyPassword = signingProps.getProperty("releaseKeyPassword")
        }
    }

    buildTypes {
        release {
            // Deliberately not minified. This is the thing you reach for when the main app is
            // broken; a few hundred kilobytes saved is not worth a shrinker surprise here.
            isMinifyEnabled = false
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// No dependencies at all beyond the Kotlin stdlib the plugin adds. This APK is bundled inside
// the main one, so every kilobyte here is a kilobyte on every install; the service uses nothing
// but framework APIs.
dependencies {
}
