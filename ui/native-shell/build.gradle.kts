plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "xin.dshaa.ui.nativeshell"
    compileSdk = 34

    defaultConfig {
        // 与 app 一致（terminal 的 PTY 依赖 minSdk 26）
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    api(composeBom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-core")
    api("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    api(project(":ui:design"))
    api(project(":ui:shell-api"))
    implementation(project(":support"))
    api(project(":core:model"))
    api(project(":core:plugins"))
    api(project(":core:llm"))
    api(project(":core:jobs"))
    api(project(":core:session")) {
        exclude(group = "com.github.luben")
    }
    api(project(":core:legacy")) {
        exclude(group = "com.github.luben")
    }
    api(project(":thirdparty:terminal"))
}
