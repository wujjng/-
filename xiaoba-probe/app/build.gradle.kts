plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiaoba.probe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaoba.probe"
        minSdk = 27          // 京鱼座 i8 Pro：Android 8.1
        targetSdk = 27       // 设备停在 8.1，不追新
        versionCode = 1
        versionName = "0.1-m1-probe"
        ndk {
            abiFilters += listOf("armeabi-v7a")   // 设备是 ARMv7 32 位
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("onnx")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true   // 把 .so 解压到磁盘，老系统加载更稳
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 只用 Kotlin 标准库和 Android 框架 API，不引入 AndroidX / GMS
}
