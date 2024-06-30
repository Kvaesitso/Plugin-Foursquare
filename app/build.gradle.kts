plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.mm20.launcher2.plugin.foursquare"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.mm20.launcher2.plugin.foursquare"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activitycompose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialicons)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.launchersdk)
    implementation(libs.kotlin.serialization)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.serialization.json)
}