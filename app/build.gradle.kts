
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nexusai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexusai.app"
        minSdk = 26
        targetSdk = 35
        // Increase versionCode for every installable update.
        versionCode = 13
        versionName = "1.4.7"
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

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Background voice service support
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
