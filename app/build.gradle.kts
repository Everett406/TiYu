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
        versionCode = 41
        versionName = "2.10.3"
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
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
