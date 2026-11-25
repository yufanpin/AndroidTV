plugins {
    id("com.android.library")
}

android {
    namespace = "com.gary.ysplive.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// 单元测试向量原先位于上游 tools/ 目录；这里随模块资源一起分发。
tasks.withType<Test>().configureEach {
    systemProperty("ysp.tools.dir", file("src/test/resources").absolutePath)
}
