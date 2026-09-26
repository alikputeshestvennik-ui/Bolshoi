plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.example.speechfilter.shared"; compileSdk = 35
    defaultConfig { minSdk = 26 }
}
dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.1")
}
