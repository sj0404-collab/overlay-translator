import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = System.getenv("SIGNING_STORE_FILE")
val releaseStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
val cyrillicAssetDir = layout.buildDirectory.dir("generated/local-cyrillic-assets")

data class LocalModel(val name: String, val path: String, val sha256: String)

val cyrillicModelPin = "0279620ace18256b36850d6773bad03ffad03fa7"
val cyrillicModels = listOf(
    LocalModel("detector.tflite", "models/tflite/pp-ocrv4_mobile_det_float32.tflite", "a2803d3c540e9077e561540285005b77dd2d47f7f5e470e8f2da2c993d9ad9f0"),
    LocalModel("recognizer_v3.tflite", "models/tflite/cyrillic_pp-ocrv3_mobile_rec_float32.tflite", "3f5fd05d9c6fc1c5b11b832963f486a7ad483eb8e58fd0f9671028119b7160a1"),
    LocalModel("recognizer_v5.tflite", "models/tflite/cyrillic_pp-ocrv5_mobile_rec_float32.tflite", "c51eb8df3eb94cce31f906c23ceb572151d27b0692a8e8bea62a38f6e54f7808"),
    LocalModel("dict_v3.txt", "models/dicts/cyrillic_dict.txt", "369a82c6c8c479784a5d726448b83b1eafb5fef0a4129a5eaa3929625ddcd132"),
    LocalModel("dict_v5.txt", "models/dicts/ppocrv5_cyrillic_dict.txt", "db40aa52ceb112055be80c694afdf655d5d2c4f7873704524cc16a447ca913ba"),
)

fun sha256(file: java.io.File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

val prepareBundledCyrillicModels by tasks.registering {
    group = "distribution"
    description = "Downloads and verifies the pinned local Cyrillic PP-OCR assets for the APK."
    outputs.dir(cyrillicAssetDir)
    doLast {
        val outputRoot = cyrillicAssetDir.get().asFile.resolve("cyrillic_ocr").apply { mkdirs() }
        cyrillicModels.forEach { model ->
            val destination = outputRoot.resolve(model.name)
            if (!destination.isFile || sha256(destination) != model.sha256) {
                val part = outputRoot.resolve("${model.name}.part")
                part.delete()
                val source = "https://raw.githubusercontent.com/sj0404-collab/ocr-rus-cyrillic/$cyrillicModelPin/${model.path}"
                URL(source).openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    getInputStream().use { input -> part.outputStream().use(input::copyTo) }
                }
                check(sha256(part) == model.sha256) { "Checksum mismatch for ${model.name}" }
                part.renameTo(destination)
            }
        }
    }
}

android {
    namespace = "com.overlay.translator"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.overlay.translator"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "6.0.0-local-frame"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    sourceSets.getByName("main").assets.srcDir(cyrillicAssetDir)
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += listOf("**/*.so")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.google.ai.edge.litert:litert:2.1.6")
}

tasks.named("preBuild").configure { dependsOn(prepareBundledCyrillicModels) }
