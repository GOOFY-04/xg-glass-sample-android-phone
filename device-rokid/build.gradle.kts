plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.rokid"
}

dependencies {
    api(project(":core"))

    // Rokid CXR-M SDK
    api("com.rokid.cxr:client-m:1.0.1-20250812.080117-2")

    // We keep JSON small and stable for custom view layout.
    api("com.google.code.gson:gson:2.10.1")

    // Required because the adapter takes an AppCompatActivity and uses AndroidX APIs.
    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.core:core-ktx:1.13.1")
}


