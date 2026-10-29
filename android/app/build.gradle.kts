plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "gr.bettinginvestor.tips"
    compileSdk = 35
    defaultConfig {
        applicationId = "gr.bettinginvestor.tips"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.7.1"
        buildConfigField("String", "API_BASE_URL", "\"https://tips-app-api.onrender.com\"")
        buildConfigField("boolean", "STUB_BILLING", "true")
        buildConfigField("boolean", "USE_LOCAL_DEMO", "false")
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug {
            val apiUrl = project.findProperty("apiUrl") as String? ?: "https://tips-app-api.onrender.com"
            buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.json:json:20240303")
    implementation("com.android.billingclient:billing-ktx:7.1.1")
}
