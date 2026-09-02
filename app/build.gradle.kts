plugins {
    id("com.android.application")
}

android {
    namespace = "org.arm.learningpath.tinysdstudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.arm.learningpath.tinysdstudio"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.pytorch:executorch-android:1.3.1")
}

apply(from = "generated-runtime-dependencies.gradle.kts")
