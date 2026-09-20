import java.util.Properties

// Release signing credentials live in local.properties, which is gitignored — the keystore and
// its passwords must never reach the repository. A checkout without them still builds; the
// release type just falls back to the debug key and says so.
val signingProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pocketssh.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pocketssh.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"

        buildConfigField("String", "UPDATE_REPO", "\"RedArtem39/PocketSSH\"")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug").also {
                logger.warn("No release keystore configured in local.properties - signing release with the debug key. Do not publish this build.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // The updater needs VERSION_NAME at runtime to compare against the latest GitHub release.
        buildConfig = true
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/**", "META-INF/INDEX.LIST") }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
