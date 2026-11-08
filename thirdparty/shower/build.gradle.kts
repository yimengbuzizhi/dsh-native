import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shower 虚拟屏客户端（Operit showerclient，LGPL-3.0）。
// 宿主 App 负责注入 ShellRunner 与转发 Binder 广播；本模块只提供客户端库。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ai.assistance.showerclient"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
        compose = false
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
