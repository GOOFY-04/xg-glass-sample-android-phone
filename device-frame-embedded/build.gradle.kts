plugins {
    id("com.universalglasses.android.library")
}

android {
    namespace = "com.universalglasses.device.frame.embedded"
}

dependencies {
    api(project(":core"))
    implementation(project(":device-frame-flutter"))

    // When frame_module is present, settings.gradle.kts applies include_flutter.groovy,
    // which adds the :flutter project that provides Flutter runtime + GeneratedPluginRegistrant.
    implementation(project(":flutter"))
}


