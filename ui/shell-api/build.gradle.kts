plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "xin.dshaa.ui.shellapi"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
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
    api(project(":core:model"))
    api(project(":core:session")) {
        // zstd 由 app 统一用 AAR（带 .so）；这里只暴露 TrashedSession 等纯类型，别把 jar 副本带进来
        exclude(group = "com.github.luben")
    }
    api(project(":core:plugins"))
    api(project(":core:skill"))
    api(project(":core:tools"))
    api(project(":core:input"))
    api(project(":core:jobs"))
    api(project(":core:agents"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
