plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kyz.downloaderhub"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.kyz.downloaderhub"
        minSdk = 24
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 7
        versionName = "1.2.0"// ADD
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

    packaging {                                      // ADD
        jniLibs {                                    // ADD
            useLegacyPackaging = true                // ADD
        }                                            // ADD
    }                                                // ADD

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")   // ADD
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")    // ADD
    implementation("androidx.media3:media3-exoplayer:1.10.0")                 // ADD (music player)
    implementation("androidx.media3:media3-session:1.10.0")                   // ADD (notification + lock-screen controls)
    implementation("com.google.guava:guava:33.0.0-android")                   // ADD (needed by the music player connection)
}