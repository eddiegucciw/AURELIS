plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// One fixed signing key, so every new build can install over the previous one.
val signingKeystore = rootProject.file(".signing/aurelis.keystore").apply {
    parentFile.mkdirs()
    writeBytes(java.util.Base64.getMimeDecoder().decode(rootProject.file("signing/keystore.b64").readText()))
}
val buildTime = System.currentTimeMillis()

android {
    namespace = "com.aurelis.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aurelis.app"
        minSdk = 26
        targetSdk = 35
        // Minutes since 1970: always increases, so newer builds always count as updates.
        versionCode = (buildTime / 60_000L).toInt()
        versionName = "1.1"
        buildConfigField("long", "BUILD_TIME", "${buildTime}L")
        ndk {
            // Modern phones only; keeps the APK smaller.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = signingKeystore
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Memory database
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Offline speech recognition + local wake word
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Offline AI brain (on-device language model)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}
