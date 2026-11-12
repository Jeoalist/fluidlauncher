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
        // Phones only; the emulator-only x86 builds of Conscrypt would add about 5 MB.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    packaging {
        resources {
            // Bouncy Castle data for algorithms the ADB certificate never uses.
            excludes += listOf("org/bouncycastle/pqc/**", "org/bouncycastle/**/CertPathReviewerMessages*.properties")
        }
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
    // Talks to the phone's own adbd over Wireless debugging, to start the display service.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Builds the self-signed certificate adbd pairs with.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
