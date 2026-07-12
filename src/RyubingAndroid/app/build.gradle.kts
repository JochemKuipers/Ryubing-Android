plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.ryubing.android"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "org.ryubing.android"
        // Kenji parity: modern arm64 devices only.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"

        ndk {
            // arm64-v8a only: the emulator core is published solely for linux-bionic-arm64.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += "-std=c++20"
            }
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Managed by the compose compiler gradle plugin.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The .so files come from the :libryubing publish + native-deps scripts.
    // Do not let AGP strip them (libryubing.so is already stripped by the publish).
    packaging {
        jniLibs {
            // adrenotools hooks install into nativeLibraryDir; compressed APK libs break hook paths.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

// Build the NativeAOT core before the native/APK build so libryubing.so is present.
tasks.named("preBuild").configure {
    dependsOn(":libryubing:assemble")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // JNA for calling the libryubing.so C ABI from Kotlin.
    implementation("net.java.dev.jna:jna:5.15.0@aar")
}
