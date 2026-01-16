plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.universalglasses.universal"
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
    // Expose the stable API surface
    api(project(":core"))

    // Always include Rokid implementation
    api(project(":device-rokid"))

    // Include Frame when available in this build (i.e., frame_module exists and is included).
    // For published artifacts, ensure the build pipeline always includes device-frame-embedded.
    if (project.findProject(":device-frame-embedded") != null) {
        api(project(":device-frame-embedded"))
    }
}


