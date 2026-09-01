pluginManagement {
    repositories {
        // maven.google.com 在部分网络下被代理阻断（502），dl.google.com 是其镜像宿主
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        google()
        mavenCentral()
    }
}

rootProject.name = "xiaozhi-android"
include(":app")
include(":core-protocol")
