plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.phoneglasses"
}

dependencies {
    api(project(":core"))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Camera (same as simulator — CameraX backed by phone camera)
    api("androidx.camera:camera-core:1.3.4")
    api("androidx.camera:camera-camera2:1.3.4")
    api("androidx.camera:camera-lifecycle:1.3.4")

    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.core:core-ktx:1.13.1")

    // HTTP client for cloud API calls
    api("com.squareup.okhttp3:okhttp:4.12.0")
}
