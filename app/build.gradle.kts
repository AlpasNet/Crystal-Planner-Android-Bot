plugins {
    id("com.android.application")
}

android {
    namespace = "net.alpas.crystalplanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.alpas.crystalplanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.11"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
