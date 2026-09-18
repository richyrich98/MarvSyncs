plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marvsync.fit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marvsync.fit"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity:1.9.3")
    implementation("com.google.android.material:material:1.12.0")

    // Publicly published JieLi wearable/RCSP SDK components.
    implementation("com.caitun.ble:jldecryption:0.4")
    implementation("com.caitun.ble:jl_rcsp:0.8.0_705")
    implementation("com.caitun.ble:jl_watch:1.14.0_11307")
}
