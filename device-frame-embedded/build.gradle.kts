plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.universalglasses.device.frame.embedded"
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
    implementation(project(":device-frame-flutter"))

    // When frame_module is present, settings.gradle.kts applies include_flutter.groovy,
    // which adds the :flutter project that provides Flutter runtime + GeneratedPluginRegistrant.
    implementation(project(":flutter"))
}


