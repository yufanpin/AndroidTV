plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tivimatelite"
    compileSdk = 35

    defaultConfig {
        // ysp 分支独立包名，可与 main 分支的 com.tivimatelite 共存
        applicationId = "com.tivimatelite.ysp"
        // 引擎内核使用 java.time / String.join（API 26）；目标机为 Android 9+
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "ysp-1.0.0"
        buildConfigField("String", "PLAYLIST_URL", "\"\"")

        ndk {
            // 同时支持 32 位 ARM（晶晨 S905L3）与 arm64 设备；AAR 两种原生库均提供。
            abiFilters += "armeabi-v7a"
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // 固定调试签名：CI 每次构建使用同一证书，升级安装不会报 signature mismatch。
        // 仅用于侧载测试，正式发布请替换为自己的 release keystore。
        getByName("debug") {
            storeFile = file("ysp-debug.jks")
            storePassword = "ysplive"
            keyAlias = "ysplive"
            keyPassword = "ysplive"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":yspcore"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // 播放内核：IJKPlayer（debugly/ijkplayer k0.8.9 预编译 AAR，自带 armeabi-v7a/arm64-v8a .so）。
    // 替代 Vulkan-only、在无 Vulkan 设备上无法构造的 FSPlayer。
    implementation(files("libs/ijkplayer-cmake-release.aar"))

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
