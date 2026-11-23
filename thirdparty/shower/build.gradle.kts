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

    sourceSets {
        getByName("main") {
            // 预编译的 shell 身份 launcher（aarch64 可执行 ELF）放在 jniLibs/arm64-v8a 下。
            // AGP 按 ABI 目录随包，所以 x86_64 包不会带它；DeviceShell 会明确记录不可用并退回
            // 旧的 uid 0 路径。可执行文件不是共享库，见 packaging.jniLibs.keepDebugSymbols。
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            // 跑 add_executable 产出的 ELF 不是常规 .so：避免 strip 任务改写它。
            keepDebugSymbols += "**/libshower_shell_exec.so"
            // launcher 要从 nativeLibraryDir 复制出来再 exec，必须解压。
            useLegacyPackaging = true
        }
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
