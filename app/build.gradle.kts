import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
fun secret(key: String): String = localProperties.getProperty(key) ?: "MISSING_${key}_SET_IN_LOCAL_PROPERTIES"

android {
    namespace = "com.moonlight.matrixmessenger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moonlight.matrixmessenger"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-beta"

        buildConfigField("String", "CLOUDFLARE_ACCOUNT_ID", "\"${secret("CLOUDFLARE_ACCOUNT_ID")}\"")
        buildConfigField("String", "CLOUDFLARE_NAMESPACE_ID", "\"${secret("CLOUDFLARE_NAMESPACE_ID")}\"")
        buildConfigField("String", "CLOUDFLARE_API_TOKEN", "\"${secret("CLOUDFLARE_API_TOKEN")}\"")
        buildConfigField("String", "GMAIL_APP_PASSWORD", "\"${secret("GMAIL_APP_PASSWORD")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.infobip:google-webrtc:1.0.39079")
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
}
