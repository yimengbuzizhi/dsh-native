import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Standalone Gradle module for OperitTerminalCore (LGPL-3.0).
// Intentionally does NOT use the Operit version catalog; versions are pinned here.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ai.assistance.operit.terminal"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // PTY executables are dlopen()ed/exec()ed from the extracted native library dir,
        // so the .so files must not stay compressed inside the APK.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // SSH client
    implementation("com.jcraft:jsch:0.1.55")

    // FTP server
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.apache.ftpserver:ftplet-api:1.2.0")

    // Embedded SSH/SFTP server
    implementation("org.apache.sshd:sshd-core:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.apache.sshd:sshd-sftp:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
}
