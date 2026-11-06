plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// release 签名：CI 通过环境变量注入（KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD）
// 本地未设置时回退 debug 签名，构建不失败
android {
    namespace = "cn.locator.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.locator.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 仅 arm64
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // 只保留中英文资源，剥离其他语言
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        create("release") {
            // 四个变量齐全且 keystore 文件存在才启用，否则保持空配置（由 buildTypes 回退 debug）
            val ks = System.getenv("KEYSTORE_FILE")
            val ksPass = System.getenv("KEYSTORE_PASSWORD")
            val alias = System.getenv("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASSWORD")
            if (!ks.isNullOrBlank() && !ksPass.isNullOrBlank() &&
                !alias.isNullOrBlank() && !keyPass.isNullOrBlank() && file(ks).exists()
            ) {
                storeFile = file(ks)
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // R8 混淆 + 资源收缩，大幅精简 APK
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val ks = System.getenv("KEYSTORE_FILE")
            val ksPass = System.getenv("KEYSTORE_PASSWORD")
            val alias = System.getenv("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASSWORD")
            val hasReleaseSigning = !ks.isNullOrBlank() && !ksPass.isNullOrBlank() &&
                !alias.isNullOrBlank() && !keyPass.isNullOrBlank() && file(ks).exists()
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle("WARN: release 签名环境变量不完整，回退 debug 签名")
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "META-INF/*"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
