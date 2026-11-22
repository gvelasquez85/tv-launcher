import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// TMDB key/region/language are set at run time on the TV (installer or web manager).
// local.properties values (tmdb.key, region, lang) are only build-time defaults for developers.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}


// One signing key for BOTH apps (they talk through a signature-protected ContentProvider).
// Provide it with env vars / local.properties, or builds fall back to this machine's debug key.
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: localProps.getProperty(prop)
val sharedKeystore = signingValue("TVL_KEYSTORE", "signing.keystore")

android {
    namespace = "app.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.tvlauncher"
        minSdk = 28
        // La TV es Android 9 (API 28); apuntar ahi evita cambios de comportamiento de versiones nuevas.
        targetSdk = 28
        versionCode = 3
        versionName = "0.3"
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("tmdb.key", "")}\"")
        buildConfigField("String", "REGION", "\"${localProps.getProperty("region", "CO")}\"")
        buildConfigField("String", "LANG", "\"${localProps.getProperty("lang", "es-CO")}\"")
    }

    signingConfigs {
        if (sharedKeystore != null) create("shared") {
            storeFile = file(sharedKeystore)
            storePassword = signingValue("TVL_KEYSTORE_PASSWORD", "signing.storePassword")
            keyAlias = signingValue("TVL_KEY_ALIAS", "signing.keyAlias")
            keyPassword = signingValue("TVL_KEY_PASSWORD", "signing.keyPassword")
        }
    }
    buildFeatures {
        buildConfig = true
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
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
