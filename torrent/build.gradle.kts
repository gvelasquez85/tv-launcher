import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


// One signing key for BOTH apps (they talk through a signature-protected ContentProvider).
// Provide it with env vars / local.properties, or builds fall back to this machine's debug key.
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: signingProps.getProperty(prop)
val sharedKeystore = signingValue("TVL_KEYSTORE", "signing.keystore")

android {
    namespace = "app.tvtorrent"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.tvtorrent"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
        // 32-bit (most older Android TVs) and 64-bit ARM TVs/boxes.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        if (sharedKeystore != null) create("shared") {
            storeFile = file(sharedKeystore)
            storePassword = signingValue("TVL_KEYSTORE_PASSWORD", "signing.storePassword")
            keyAlias = signingValue("TVL_KEY_ALIAS", "signing.keyAlias")
            keyPassword = signingValue("TVL_KEY_PASSWORD", "signing.keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
        }
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
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
