pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dsh-native"
include(":app")
include(":core:model")
include(":core:session")
include(":core:legacy")
include(":core:backup")
include(":core:protocol")
include(":core:llm")
include(":core:prompt")
include(":core:skill")
include(":core:input")
include(":core:js")
include(":core:plugins")
include(":core:workflow")
include(":core:tools")
include(":core:jobs")
include(":core:agents")
include(":core:agent-api")
include(":core:capability-api")
include(":core:engine")
include(":core:attachment")
include(":core:parity")
include(":ui:design")
include(":support")
include(":ui:shell-api")
include(":ui:native-shell")

// 第三方（LGPL-3.0，源码复制，见 NOTICE.md）
include(":thirdparty:shower")
include(":thirdparty:terminal")
