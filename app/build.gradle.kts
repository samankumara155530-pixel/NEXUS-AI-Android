
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.nexusai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexusai.app"
        minSdk = 26
        targetSdk = 35
        // Increase versionCode for every installable update.
        versionCode = 14
        versionName = "1.5.0"
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("NEXUS_KEYSTORE_PATH")
            val storePass = System.getenv("NEXUS_KEYSTORE_PASSWORD")
            val keyAliasValue = System.getenv("NEXUS_KEY_ALIAS")
            val keyPass = System.getenv("NEXUS_KEY_PASSWORD")
            if (!storePath.isNullOrBlank() &&
                !storePass.isNullOrBlank() &&
                !keyAliasValue.isNullOrBlank() &&
                !keyPass.isNullOrBlank()
            ) {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Separate installable roles while preserving the same package ID and signing key.
    // Each APK is intended for a different physical phone.
    flavorDimensions += "deviceRole"
    productFlavors {
        create("controller") {
            dimension = "deviceRole"
            buildConfigField("String", "NEXUS_DEVICE_ROLE", "\"controller\"")
            versionNameSuffix = "-controller"
            manifestPlaceholders["appLabel"] = "NEXUS AI Controller"
        }
        create("target") {
            dimension = "deviceRole"
            buildConfigField("String", "NEXUS_DEVICE_ROLE", "\"target\"")
            versionNameSuffix = "-target"
            manifestPlaceholders["appLabel"] = "NEXUS AI Target"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        )
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Firebase Cloud Messaging for authenticated device wake signals.
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-messaging")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // CameraX: visible, user-started Target camera preview.
    val cameraXVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // Background voice service support
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
