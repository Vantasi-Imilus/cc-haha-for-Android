plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cchaha.android"
    compileSdk = 34
    // 使用较新的 build-tools（36.0.0），与常见 SDK 管理器安装的版本一致
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.cchaha.android"
        // targetSdk 28 是关键决策：Android 10+ 对 targetSdk>=29 的应用
        // 禁止在私有目录执行二进制（W^X）。本应用需要把 Node 运行时与
        // 各语言工具链解压到 filesDir 后直接 exec（Termux 同款方案），
        // 因此保持 28。不走 Google Play，无上架合规问题。
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // assets 里有大文件（runtime.tar.gz / h5 / toolchain），关闭压缩
    androidResources {
        noCompress += listOf("gz", "tar", "zst", "xz", "so", "deb")
    }

    lint {
        abortOnError = false
    }

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Shizuku：shell 级权限（用户安装 Shizuku 后可用），
    // 用于放开受限目录访问与以 shell 身份执行命令
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
