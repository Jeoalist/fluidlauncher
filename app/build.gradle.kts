plugins {
    id("com.android.application")
}

android {
    namespace = "dev.jeo.pencere"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.jeo.pencere"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
    }
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
