plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xiaozhi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xiaozhi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
    }

    signingConfigs {
        create("release") {
            // 优先用 CI/本地环境变量注入的正式密钥；未注入时回落到仓库内的测试 keystore，
            // 保证任何人 clone 后都能直接打出可安装、可覆盖升级的 release 包。
            // 正式对外发布前务必换成私有 keystore，并通过下列环境变量注入：
            //   XIAOZHI_KEYSTORE / XIAOZHI_STORE_PASSWORD / XIAOZHI_KEY_ALIAS / XIAOZHI_KEY_PASSWORD
            storeFile = System.getenv("XIAOZHI_KEYSTORE")?.let { file(it) }
                ?: rootProject.file("keystore/release-test.jks")
            storePassword = System.getenv("XIAOZHI_STORE_PASSWORD") ?: "xiaozhi123456"
            keyAlias = System.getenv("XIAOZHI_KEY_ALIAS") ?: "xiaozhi"
            keyPassword = System.getenv("XIAOZHI_KEY_PASSWORD") ?: "xiaozhi123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core-protocol"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
