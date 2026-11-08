import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * QuickJS（MIT）Android 绑定：给 `core:workflow`（以及后续插件运行时）提供真正的 JS 引擎。
 *
 * 为什么自带 JNI 而不是用现成 AAR：workflow 的 `agent()` 是**异步** host hook（Promise），
 * 需要「JS 线程等宿主完成 → 宿主线程回灌 completion → JS 线程 resolve Promise」的 job queue；
 * 现成绑定只暴露同步 evaluate，做不出真并发。C 侧只做 JS 值与 JNI 边界，异步语义在 Kotlin 侧。
 *
 * 只出 arm64-v8a（本机 PLT120 是 arm64；多 ABI 会让 APK 里多两份 quickjs，收益为零）。
 */
android {
    namespace = "xin.dshaa.core.js"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += "-std=c++17"
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(kotlin("test"))
}
