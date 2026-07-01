plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.offlineinc.voicetotext"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.offlineinc.voicetotext"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The TCL Flip 2 is a 32-bit ARM phone; only build for its chip.
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Force the native engine to compile optimized even in debug
                // builds — whisper is unusably slow otherwise — and turn on
                // ARM NEON vectorization for this 32-bit chip.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_ARM_NEON=ON"
            }
        }
    }

    // Native whisper.cpp build (see src/main/cpp/CMakeLists.txt).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Don't compress the model file in the APK (we copy it out as-is).
    androidResources {
        noCompress += "bin"
    }

    signingConfigs {
        create("release") {
            // In CI, the beta-release workflow supplies these via env vars
            // (decoded from the KEYSTORE_BASE64 secret). Local builds leave
            // them unset and fall back to debug signing.
            System.getenv("KEYSTORE_PATH")?.let { ksPath ->
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Only sign with the release key when CI provided the keystore,
            // so a local `assembleRelease` without secrets doesn't fail.
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.juni