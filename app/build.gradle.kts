import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val threadProfile = ((project.findProperty("trpg.threadProfile") as String?) ?: "A").uppercase()
check(threadProfile in setOf("A", "B", "C")) {
    "trpg.threadProfile must be one of A, B, C (was: $threadProfile)"
}

// Stable debug signing: CI restores a fixed debug keystore (see android.yml) so that
// `adb install -r` keeps working across builds. Without this the runner would generate a
// fresh random keystore on every run and every install would need an uninstall first.
val debugKeystoreFile = file(
    System.getenv("TRPG_DEBUG_KEYSTORE")
        ?: "${System.getProperty("user.home")}/.android/debug.keystore",
)

android {
    namespace = "io.github.shiokunn.trpg"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile.isFile) {
                storeFile = debugKeystoreFile
                storeType = "PKCS12"
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.shiokunn.trpg"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "THREAD_PROFILE", "\"$threadProfile\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // CPU inference is validated for arm64 only. A Vulkan build uses the same ABI.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments.addAll(
                    listOf(
                        "-DBUILD_SHARED_LIBS=ON",
                        "-DLLAMA_BUILD_COMMON=ON",
                        "-DLLAMA_BUILD_TESTS=OFF",
                        "-DLLAMA_BUILD_TOOLS=OFF",
                        "-DLLAMA_BUILD_EXAMPLES=OFF",
                        "-DLLAMA_BUILD_SERVER=OFF",
                        "-DLLAMA_BUILD_APP=OFF",
                        "-DLLAMA_OPENSSL=OFF",
                        "-DGGML_NATIVE=OFF",
                        "-DGGML_BACKEND_DL=ON",
                        "-DGGML_CPU_ALL_VARIANTS=ON",
                        "-DGGML_LLAMAFILE=OFF",
                        "-DGGML_OPENMP=OFF",
                        "-DTRPG_THREAD_PROFILE=$threadProfile",
                    ),
                )
                if ((project.findProperty("trpg.vulkan") as String?) == "true") {
                    arguments.add("-DGGML_VULKAN=ON")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // ggml loads its CPU backends from nativeLibraryDir at runtime. With the
            // default APK-embedded packaging that directory is empty, so the model
            // loader reports "no backends are loaded". Extracting the .so files
            // makes ggml_backend_load_all_from_path() work.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
