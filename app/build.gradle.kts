import java.io.File
import java.net.URL
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// One fixed signing key, so every new build can install over the previous one.
val signingKeystore = rootProject.file(".signing/aurelis.keystore")
signingKeystore.parentFile.mkdirs()
signingKeystore.writeBytes(Base64.getMimeDecoder().decode(rootProject.file("signing/keystore.b64").readText()))

val buildTime = System.currentTimeMillis()

// Artwork for the app icon and the centre of the Home screen. A picture uploaded to the top
// folder of the repo wins; otherwise the built-in artwork (text parts in signing/art*.b64) is used.
val artRes = layout.buildDirectory.dir("generated/aurelisArt/res").get().asFile
artRes.deleteRecursively()
artRes.mkdirs()
val uploadedArt = rootProject.projectDir.listFiles()
    ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
    ?.maxByOrNull { it.length() }
val builtInArtParts = (rootProject.file("signing").listFiles() ?: emptyArray())
    .filter { it.name.startsWith("art") && it.name.endsWith(".b64") }
    .sortedBy { it.name }
val builtInArt = if (uploadedArt == null && builtInArtParts.isNotEmpty()) {
    val f = layout.buildDirectory.file("generated/aurelisArt/builtin.webp").get().asFile
    f.writeBytes(Base64.getMimeDecoder().decode(builtInArtParts.joinToString("") { it.readText() }))
    f
} else {
    null
}
val artSource = uploadedArt ?: builtInArt
artSource?.let { src ->
    src.copyTo(artRes.resolve("drawable-nodpi/aurelis_art." + src.extension.lowercase()), overwrite = true)
    artRes.resolve("drawable").mkdirs()
    artRes.resolve("drawable/aurelis_art_fg.xml").writeText(
        """<?xml version="1.0" encoding="utf-8"?>
<inset xmlns:android="http://schemas.android.com/apk/res/android"
    android:drawable="@drawable/aurelis_art" android:inset="16dp" />
"""
    )
    artRes.resolve("mipmap-anydpi-v26").mkdirs()
    artRes.resolve("mipmap-anydpi-v26/ic_launcher_art.xml").writeText(
        """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_background" />
    <foreground android:drawable="@drawable/aurelis_art_fg" />
</adaptive-icon>
"""
    )
}

// Offline speech built in: the build machine downloads the English speech pack once and packs it
// into the app, so listening works offline from the first launch with nothing to download.
val voskZip = rootProject.file(".cache/vosk-model-small-en-us-0.15.zip")
if (!voskZip.exists() || voskZip.length() < 30_000_000L) {
    voskZip.parentFile.mkdirs()
    try {
        val part = File(voskZip.path + ".part")
        URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openStream().use { input ->
            part.outputStream().use { input.copyTo(it) }
        }
        if (part.length() > 30_000_000L) part.renameTo(voskZip) else part.delete()
    } catch (e: Exception) {
        println("AURELIS: couldn't download the offline speech pack: " + e.message)
    }
}
val speechAssets = layout.buildDirectory.dir("generated/aurelisAssets").get().asFile
speechAssets.deleteRecursively()
speechAssets.mkdirs()
if (voskZip.exists()) voskZip.copyTo(speechAssets.resolve("vosk-model.zip"), overwrite = true)

android {
    namespace = "com.aurelis.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aurelis.app"
        minSdk = 26
        targetSdk = 35
        // Minutes since 1970: always increases, so newer builds always count as updates.
        versionCode = (buildTime / 60_000L).toInt()
        versionName = "2.0"
        manifestPlaceholders["appIcon"] = if (artSource != null) "@mipmap/ic_launcher_art" else "@mipmap/ic_launcher"
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

    sourceSets["main"].res.srcDir(artRes)
    sourceSets["main"].assets.srcDir(speechAssets)
    androidResources {
        noCompress += "zip"
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
