plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningVariables = listOf(
    "ANDROID_KEYSTORE_PATH",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)
val releaseSigningEnvironment = releaseSigningVariables.associateWith {
    providers.environmentVariable(it).orNull
}
val configuredReleaseSigningVariables = releaseSigningEnvironment.values.count { !it.isNullOrBlank() }
val releaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

if (configuredReleaseSigningVariables !in listOf(0, releaseSigningVariables.size)) {
    error("Release signing environment is incomplete. Set all required ANDROID_* variables.")
}
if (releaseTaskRequested && configuredReleaseSigningVariables != releaseSigningVariables.size) {
    error("Release tasks require signing credentials from environment variables.")
}

android {
    namespace = "com.block154.courierpilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.block154.courierpilot"
        minSdk = 30
        targetSdk = 35
        versionCode = 24
        versionName = "0.12.2"
    }

    signingConfigs {
        create("release") {
            if (configuredReleaseSigningVariables == releaseSigningVariables.size) {
                storeFile = file(releaseSigningEnvironment.getValue("ANDROID_KEYSTORE_PATH")!!)
                storePassword = releaseSigningEnvironment.getValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("ANDROID_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("ANDROID_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("androidx.core:core-ktx:1.15.0")

    // Keep UI dependencies on the API-35 generation. Activity 1.11+ is compiled with API 36.
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.foundation:foundation:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
