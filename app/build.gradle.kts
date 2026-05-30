plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tivimatelite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tivimatelite"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "PLAYLIST_URL", "\"http://192.168.10.240:5220\"")
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

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
