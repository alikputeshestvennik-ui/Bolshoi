plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.speechfilter"
    signingConfigs {
        create("release") {
            val ks = System.getenv("ANDROID_KEYSTORE_PATH")
            val alias = System.getenv("ANDROID_KEY_ALIAS")
            val storePass = System.getenv("ANDROID_STORE_PASSWORD")
            val keyPass = System.getenv("ANDROID_KEY_PASSWORD")
            if (!ks.isNullOrBlank() && !alias.isNullOrBlank() && !storePass.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                storeFile = file(ks)
                keyAlias = alias
                storePassword = storePass
                keyPassword = keyPass
            }
        }
    }
    compileSdk = 35
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("release") }
    }
    defaultConfig { applicationId = "com.example.speechfilter"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0.0" }
}
dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
