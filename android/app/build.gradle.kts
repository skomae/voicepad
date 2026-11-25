import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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
// Silero voice-activity detector (MIT, ~640 KB), bundled so silence trimming
// and pause detection work offline from the very first recording.
val sileroVad = layout.projectDirectory.file("src/main/assets/silero_vad.onnx")
val downloadSileroVad by tasks.registering {
    description = "Downloads the Silero VAD model into assets."
    val target = sileroVad.asFile
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        target.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        logger.lifecycle("Downloading $url")
        val tmp = File(target.path + ".part")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(target)
    }
}
// llama.cpp source for the on-device LLM engine; fetched once and kept out of
// git (like the sherpa-onnx AAR and Silero VAD above).
val llamaCppVersion = "1.0.0"
val llamaCppDir = layout.projectDirectory.dir("cpp/llama.cpp")

val downloadLlamaCpp by tasks.registering {
    description = "Downloads the llama.cpp source used by the on-device LLM engine."
    val marker = llamaCppDir.file(".complete")
    outputs.file(marker)
    onlyIf { !marker.asFile.exists() }
    doLast {
        val url = "https://github.com/ggml-org/llama.cpp/archive/refs/tags/v$llamaCppVersion.tar.gz"
        logger.lifecycle("Downloading $url")
        llamaCppDir.asFile.mkdirs()
        val tmp = File(llamaCppDir.asFile, "llama.cpp.tar.gz")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val tar = ProcessBuilder(
            "tar", "-xzf", tmp.absolutePath, "-C", llamaCppDir.asFile.absolutePath, "--strip-components=1",
        ).inheritIO().start()
        check(tar.waitFor() == 0) { "Extracting llama.cpp failed." }
        tmp.delete()
        marker.asFile.writeText(llamaCppVersion)
    }
}
// Chinese–English dictionary for the word breakdown, bundled as an asset so
// it works offline. Built once from two downloads and kept out of git (like
// the sherpa-onnx AAR and the Silero VAD model):
//
// - CC-CEDICT (CC BY-SA 4.0, https://cc-cedict.org): words, readings, meanings.
// - jieba's word list (MIT, https://github.com/fxsjy/jieba): word frequencies,
//   used to split sentences into words and rank meanings.
//
// Output: assets/zh_dict.txt.gz, CC-CEDICT's lines each prefixed with the
// word's jieba frequency and a tab (see ZhDictionary). Being derived from
// CC-CEDICT, the file is under CC BY-SA 4.0 too; its header says so.

val zhDictionary = layout.projectDirectory.file("src/main/assets/zh_dict.txt.gz")
val downloadChineseDictionary by tasks.registering {
    description = "Downloads CC-CEDICT and jieba's word frequencies and merges them into the dictionary asset."
    val target = zhDictionary.asFile
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        fun fetch(url: String): ByteArray {
            logger.lifecycle("Downloading $url")
            val connection = URI(url).toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            return connection.getInputStream().use { it.readBytes() }
        }
        // MDBG hosts CC-CEDICT; the Zhongwen browser extension keeps a current copy on GitHub.
        val cedict = runCatching {
            GZIPInputStream(fetch("https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz").inputStream())
                .use { it.readBytes() }
        }.recoverCatching {
            logger.warn("MDBG download failed (${it.message}); trying the GitHub copy.")
            fetch("https://raw.githubusercontent.com/cschiller/zhongwen/master/data/cedict_ts.u8")
        }.getOrThrow().toString(Charsets.UTF_8)
        check(cedict.lineSequence().count { !it.startsWith("#") } > 100_000) { "CC-CEDICT download looks incomplete." }

        val jieba = fetch("https://raw.githubusercontent.com/fxsjy/jieba/v0.42.1/jieba/dict.txt").toString(Charsets.UTF_8)
        val frequency = HashMap<String, Int>(400_000)
        jieba.lineSequence().forEach { line ->
            val parts = line.split(' ')
            if (parts.size >= 2) parts[1].toIntOrNull()?.let { frequency[parts[0]] = it }
        }
        check(frequency.size > 100_000) { "jieba word list looks incomplete." }

        target.parentFile.mkdirs()
        val tmp = File(target.path + ".part")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("# VoicePad Chinese dictionary: CC-CEDICT with jieba word frequencies.\n")
            out.write("# CC-CEDICT: https://cc-cedict.org, licensed CC BY-SA 4.0; so is this file.\n")
            out.write("# jieba word frequencies: https://github.com/fxsjy/jieba (MIT).\n")
            cedict.lineSequence().forEach { line ->
                if (line.startsWith("#!")) out.write("$line\n") // version and date
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val simplified = line.substringBefore(" [").split(' ').getOrNull(1) ?: return@forEach
                out.write("${frequency[simplified] ?: 0}\t$line\n")
            }
        }
        tmp.renameTo(target)
    }
}

tasks.named("preBuild") { dependsOn(downloadSherpaOnnx, downloadSileroVad, downloadLlamaCpp, downloadChineseDictionary) }

// Release signing comes from environment variables (set by CI from repo
// secrets). Without them the release build falls back to the debug key.
val keystorePath: String? = System.getenv("VOICEPAD_KEYSTORE_PATH")

android {
    namespace = "com.voicepad.app"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.voicepad.app"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VOICEPAD_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VOICEPAD_VERSION_NAME") ?: "0.1.0"

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_CPP_DIR=${llamaCppDir.asFile.absolutePath}",
                )
            }
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
        debug {
            // x86_64 keeps the emulator working during development.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // Every current phone is arm64; leaving out the emulator ABI halves the APK.
            ndk { abiFilters += listOf("arm64-v8a") }
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

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // Extract native libraries to nativeLibraryDir: llama.cpp loads its CPU
        // backend variants from there at runtime (GGML_BACKEND_DL), the same
        // as upstream's examples/llama.android (extractNativeLibs=true).
        jniLibs.useLegacyPackaging = true
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
    // Even Realities G1 glasses over Bluetooth LE (the vendored Basis protocol in
    // glasses/basis uses the Nordic BLE libraries).
    implementation("no.nordicsemi.android:ble-ktx:2.9.0")
    implementation("no.nordicsemi.android:ble-common:2.9.0")
    implementation("no.nordicsemi.android.support.v18:scanner:1.6.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    // A user-chosen folder that keeps downloaded models across reinstalls.
    implementation("androidx.documentfile:documentfile:1.1.0")
    // Fold state, to open conversation mode when a foldable is unfolded.
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    testImplementation("junit:junit:4.13.2")
    // Same ICU transforms Android ships, for testing romanisation on the JVM.
    testImplementation("com.ibm.icu:icu4j:78.1")
}
