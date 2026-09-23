plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voiceforge.app"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.voiceforge.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Local ONNX Runtime — runs the exported XTTS-v2 graphs on-device.
    // 1.18.0 rejects the exported graphs on session load
    // ("Invalid ExecutionOrder"); 1.30.0 is the release validated by the
    // Python onnxruntime used in onnx_check.py — keep both sides in sync.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    // MP3 playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // ---- JVM unit tests (headless validation of the DSP/tokenizer/ONNX code)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")
    // real org.json for tests (android.jar's copy is stubbed)
    testImplementation("org.json:json:20240303")
    // desktop ONNX Runtime so tests can execute the exported graphs on the JVM
    // (same 1.30.0 release as the Android artifact and the Python runtime)
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
}
