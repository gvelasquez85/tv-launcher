import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La clave de TMDB vive en local.properties (fuera de git), nunca en el codigo.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

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
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
