plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Секреты из GitHub Actions (env) вшиваются в BuildConfig. Локально без них — пустые строки. */
fun secret(n: String): String = "\"" + (System.getenv(n) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "ru.alim.agent"
    compileSdk = 34
    defaultConfig {
        applicationId = "ru.alim.agent"
        minSdk = 26
        targetSdk = 34
        // Номер растёт с каждой сборкой — обновление ставится поверх
        versionCode = 1 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.0"
        buildConfigField("String", "GPTUNNEL_KEY", secret("GPTUNNEL_KEY"))
        buildConfigField("String", "YOOMONEY_CLIENT_ID", secret("YOOMONEY_CLIENT_ID"))
        // Яндекс Поиск API: свой ключ или общий ключ Yandex Cloud из Маршрута; каталог — отдельно или из адреса модели Qwen
        buildConfigField("String", "YANDEX_SEARCH_KEY", secret("YANDEX_SEARCH_KEY"))
        buildConfigField("String", "YANDEX_API_KEY", secret("YANDEX_API_KEY"))
        buildConfigField("String", "YANDEX_FOLDER_ID", secret("YANDEX_FOLDER_ID"))
        buildConfigField("String", "QWEN_MODEL", secret("QWEN_MODEL"))
    }
    val ksPath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
