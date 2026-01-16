plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.universalglasses.device.rayneo.installer"
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

    // ADB-over-TCP client library (talks to adbd:5555 directly)
    api("com.tananaev:adblib:1.3")
}


