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
        versionCode = 3
        versionName = "1.0.0"
    }
    // The keystore and its passwords live outside the repository, in ~/.gradle/gradle.properties.
    val storeFilePath = providers.gradleProperty("FLUID_STORE_FILE").orNull
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("FLUID_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("FLUID_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("FLUID_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            // Off: the display service is reached through reflection that shrinking would break.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
