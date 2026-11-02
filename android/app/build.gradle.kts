import java.util.Properties

plugins {
    id("com.android.application") version "9.5.0"
    // Kotlin itself is AGP 9's built-in (bundled KGP 2.2.10); only the
    // Compose compiler plugin is applied, at the bundled version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "dev.warden.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.warden.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties().apply {
                val f = rootProject.file("../keystore/signing.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            if (!props.isEmpty) {
                storeFile = rootProject.file("../keystore/${props.getProperty("storeFile")}")
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // A stable signature, so a shared APK can be upgraded in place.
            // The key lives in ../../keystore (kept out of any publish).
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // The OCR engine behind `OcrSink` (Apache-2.0).
    implementation("com.google.mlkit:text-recognition:16.0.1")
}

// The Rust core: libwarden.so built by cargo-ndk into src/main/jniLibs,
// which AGP packages as-is. Gradle itself runs on the Android Studio JBR
// (Java 25), so there is no JDK for cargo to care about — cargo-ndk brings
// its own linker via ANDROID_NDK_HOME.
val rustBuild = tasks.register<Exec>("buildRust") {
    description = "Builds the Rust core (libwarden.so) with cargo-ndk."
    workingDir = file("../..")
    environment(
        "ANDROID_NDK_HOME",
        System.getenv("ANDROID_NDK_HOME") ?: "/opt/homebrew/share/android-ndk",
    )
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-P", "26",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "--lib", "--profile", "android-release",
    )
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(rustBuild) }
