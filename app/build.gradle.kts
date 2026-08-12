plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.block154.couriernotificationlistener"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.block154.couriernotificationlistener"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
