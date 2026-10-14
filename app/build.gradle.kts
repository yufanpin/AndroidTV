plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tivimatelite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tivimatelite"
        // 播放内核（IJKPlayer AAR）minSdk 为 21；S905L3 设备为 Android 9，无碍。
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "PLAYLIST_URL", "\"\"")

        ndk {
            // 同时支持 32 位 ARM（晶晨 S905L3）与 arm64 设备；AAR 两种原生库均提供。
            abiFilters += "armeabi-v7a"
            abiFilters += "arm64-v8a"
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
