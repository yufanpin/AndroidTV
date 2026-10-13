plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tivimatelite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tivimatelite"
        // FSPlayer AAR (1.1.1) 要求 minSdk >= 24；S905L3 设备为 Android 9，无碍。
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "PLAYLIST_URL", "\"\"")

        ndk {
            // 晶晨 S905L3 为 32 位 ARM；FSPlayer AAR 提供 armeabi-v7a 原生库。
            abiFilters += "armeabi-v7a"
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

    // 播放内核：FSPlayer（ijkplayer 的活跃维护后继，Maven 依赖自带 armeabi-v7a/arm64-v8a .so）
    implementation("io.github.debugly:fsplayer:1.1.1")

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
