plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tvtorrent"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.tvtorrent"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
        // La TV es de 32 bits (armeabi-v7a): solo se empaqueta esa libreria nativa.
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-35")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
