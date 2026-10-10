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
        versionCode = 71
        versionName = "2.19.15"
        // v2.12.1：只留 ARM 双架构（模拟器 x86 系无真机价值）
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
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
}

dependencies {
    // v2.17.0 版本升级的实测边界（务必先读）：
    //   · navigation-compose 2.10.2 / lifecycle 2.11.0（activity-compose 1.13.0 会传递引入）
    //     均要求「AGP ≥ 9.1.0 + compileSdk ≥ 37」，在 AGP 8.13.2 上直接构建失败；
    //   · 要吃到这几版，等于先升 AGP 9 / Gradle 9 / compileSdk 37，并处理
    //     AGP 9 内置 Kotlin 迁移（org.jetbrains.kotlin.android 与新 DSL 不兼容）。
    //   故本版只升「不牵动工具链」的一档，其余留待专门的工具链升级轮。
    // v2.17.0：Compose BOM 暂留在 2025.06.01（Compose 1.8.x）。
    // 目标是把 BOM 推到 2026.09.x（Compose 1.12.1）并顺带启用官方渐进模糊 API
    // Modifier.blur(BlurRadiusSpec)，但 Compose 1.13-alpha 会把库的 compileSdk 抬到 37.1，
    // 进而牵动 AGP 9 / Gradle 9 / 内置 Kotlin 迁移——代价与收益不成比例。
    // 渐进模糊改走 ui/glass/ProgressiveBlur.kt（分层固定半径，API 31+ 可用），
    // BOM 升级留作独立一轮，在有 Android Studio 的机器上用 Upgrade Assistant 做更稳妥。
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
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

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    // v2.15.0：移除 work-runtime——每日提醒调度引擎换 AlarmManager 精确闹钟，
    // WorkManager OneTime 自续在 ROM 杀后台下蒸发（「打开 APP 才通知」根因），全仓已无 androidx.work 引用

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // v2.17.0：拍照搜题整体下线，随之移除的依赖包括 ML Kit 中文离线识别（bundled，约 18MB）、
    // 拍照 EXIF 摆正库、以及自建取景页用的 CameraX 四件套。
    // 至此应用回到「零联网 OCR / 零 native 代码 / 零相机依赖」的纯离线形态。
}
