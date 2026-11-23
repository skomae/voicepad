import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// sherpa-onnx (on-device Whisper / Parakeet) ships as an AAR on GitHub
// Releases rather than Maven, so fetch it once into app/libs.
val sherpaOnnxVersion = "2.0.3"
val sherpaOnnxAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")

val downloadSherpaOnnx by tasks.registering {
    description = "Downloads the sherpa-onnx Android library."
    val target = sherpaOnnxAar.asFile
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        target.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
        logger.lifecycle("Downloading $url")
        val tmp = File(target.path + ".part")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(target)
    }
}
tasks.named("preBuild") { dependsOn(downloadSherpaOnnx) }

// Release signing comes from environment variables (set by CI from repo
// secrets). Without them the release build falls back to the debug key.
val keystorePath: String? = System.getenv("VOICEPAD_KEYSTORE_PATH")

android {
    namespace = "com.voicepad.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voicepad.app"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VOICEPAD_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VOICEPAD_VERSION_NAME") ?: "0.1.0"
        ndk {
            // Phones are arm64; x86_64 keeps the emulator working.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("VOICEPAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VOICEPAD_KEY_ALIAS")
                keyPassword = System.getenv("VOICEPAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(files(sherpaOnnxAar))

    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
}
