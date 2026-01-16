plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.universalglasses.device.rokid"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
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


