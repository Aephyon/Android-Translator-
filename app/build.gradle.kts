plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.chattranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.chattranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.0"

        // Phones are ARM; dropping x86 builds of the ML Kit native libraries roughly halves the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // `-PwithX86` adds x86_64 for testing on an emulator.
            if (project.hasProperty("withX86")) abiFilters += "x86_64"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload, never a Play upload: the debug key lets the APK install as-is.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    testImplementation(libs.junit)
}
