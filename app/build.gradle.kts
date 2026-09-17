import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.drone.quiz"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.drone.quiz"
        minSdk = 31
        targetSdk = 35
        versionCode = 53
        versionName = "2.16.0"
        // v2.12.1：只留 ARM 双架构（模拟器 x86 系无真机价值）；
        // v2.16.0：ML Kit bundled 移除后，native 库仅剩 jniLibs 里的 MNN 推理库
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        ndkVersion = "27.2.12479018"
    }

    // 固定签名：本地（环境变量 DQ_KS_PATH/DQ_KS_STORE_PASS）与 GitHub Actions（secrets）共用同一 keystore，
    // 保证所有渠道构建的 APK 签名一致，可覆盖安装、无需卸载旧版。
    val ksPath = System.getenv("DQ_KS_PATH")
    val ksStorePass = System.getenv("DQ_KS_STORE_PASS")
    val hasDqKeystore = !ksPath.isNullOrBlank() && File(ksPath).exists() && !ksStorePass.isNullOrBlank()

    signingConfigs {
        if (hasDqKeystore) {
            create("dq") {
                storeFile = File(ksPath!!)
                storePassword = ksStorePass
                keyAlias = "dronequiz"
                keyPassword = ksStorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasDqKeystore) signingConfigs.getByName("dq") else signingConfigs.getByName("debug")
        }
        debug {
            if (hasDqKeystore) signingConfig = signingConfigs.getByName("dq")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs += listOf("-Xcontext-parameters")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // v2.16.0 拍照搜题 OCR：MNN 推理薄封装（预/后处理在 Kotlin），
    // 模型文件不随 APK 分发，首次使用时按需下载（OcrModels）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-util")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.navigation:navigation-compose:2.9.8")

    // 官方同款连续曲率形状（Capsule/RoundedRectangle）已改为源码 vendor 到 com.kyant.shapes
    // （maven 坐标 io.github.kyant0:shapes:1.2.1 的 AAR 要求 compileSdk 37 + AGP 9.1，
    //  会连带把 Compose 拉到 1.12，工程工具链暂不跟进；源码仅依赖 compose-ui，直接内联）

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    // v2.15.0：移除 work-runtime——每日提醒调度引擎换 AlarmManager 精确闹钟，
    // WorkManager OneTime 自续在 ROM 杀后台下蒸发（「打开 APP 才通知」根因），全仓已无 androidx.work 引用

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // v2.16.0：ML Kit text-recognition-chinese bundled（约 18MB：双 ABI 引擎库+内置模型）
    // 与 gms Task 桥接库一并移除——OCR 换 PaddleOCR PP-OCRv4 + MNN（模型首用按需下载），
    // APK 从 60.9MB 减至约 44MB。详见 ocr/OcrModels.kt 与 static_check 3.14 节
    // 拍照 EXIF 方向读取
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // v2.13.0 拍照搜题自建相机页：CameraX 取景框引导（比系统相机随手拍识别率显著更高）
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
}
