plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = System.getenv("SIGNING_STORE_FILE")
val releaseStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")

android {
    namespace = "com.overlay.translator"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.overlay.translator"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "5.2.0-online"
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
}