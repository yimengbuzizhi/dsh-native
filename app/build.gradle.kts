import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "xin.dshaa.nativeapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "xin.dshaa.nativeapp"
        // showerclient / OperitTerminalCore 都是 minSdk 26（虚拟屏与 PTY 的最低要求）
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0-alpha.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time（提醒的时区/夏令时计算）在 minSdk 24 上要靠 desugar 补
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // sshd / ftpserver 的 jar 各自带了 DEPENDENCIES / LICENSE / NOTICE，全部排除（不是运行时资源）
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/NOTICE.md",
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
        )
        // PTY（OperitTerminalCore）要从 APK 里直接执行 .so：必须解压 native libs
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // 悬浮球跟随后台（2026-11-09）：ProcessLifecycleOwner 判断整个应用是否在前台
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    // 悬浮窗里的 ComposeView 需要 ViewTreeSavedStateRegistryOwner（不然 Composed into the View... 崩溃）
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    // 屏幕圈选 OCR（Operit 用 ML Kit 中文+拉丁双识别，docs/76 §十 O9）
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation(project(":ui:design"))
    implementation(project(":core:model"))
    // zstd：core:session 用普通 jar 编译/测试，Android 运行时用同一版本的 AAR（带 .so）
    implementation(project(":core:session")) {
        exclude(group = "com.github.luben")
    }
    implementation(project(":core:legacy")) {
        exclude(group = "com.github.luben")
    }
    implementation(project(":core:backup"))
    implementation("com.github.luben:zstd-jni:1.5.6-8@aar")
    implementation(project(":core:protocol"))
    implementation(project(":core:llm"))
    implementation(project(":core:prompt"))
    implementation(project(":core:tools"))
    implementation(project(":core:skill"))
    implementation(project(":core:input"))
    implementation(project(":core:agents"))
    implementation(project(":core:engine")) {
        exclude(group = "com.github.luben")
    }
    implementation(project(":core:attachment"))
    implementation(project(":core:parity"))
    implementation(project(":core:plugins"))
    // workflow 工具：QuickJS 引擎（core:js，arm64 JNI）+ 纯 JVM 引擎（core:workflow）
    implementation(project(":core:js"))
    implementation(project(":core:workflow"))
    // 设备能力：虚拟屏（showerclient）与终端（OperitTerminalCore），LGPL-3.0 源码复制
    implementation(project(":thirdparty:shower"))
    implementation(project(":thirdparty:terminal"))

    // Shizuku：清单里声明了 rikka.shizuku.ShizukuProvider（ADB 级 shell 的 binder 入口），
    // 但先前只靠反射调用、没引依赖 —— 导致每次冷启动 "ClassNotFoundException: ShizukuProvider" 闪退。
    // provider 提供 ShizukuProvider/BinderContainer 两个类，api 提供 Shizuku 本体。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // aidl：IShizukuService / IRemoteProcess。Shizuku 13 的 api artifact 里没有 newProcess，
    // 必须用 ShizukuBinderWrapper 包 binder 后调 service.newProcess（虚拟屏/终端靠这条链路）。
    implementation("dev.rikka.shizuku:aidl:13.1.5")

    // java.time 的 desugar 运行时（minSdk 24）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
