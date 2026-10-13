import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Same release key as Folio (shared signature permission): keystore.properties at the repo root, or FOLIO_* env vars in CI.
val releaseSigningProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
    fun fromEnv(key: String, env: String) {
        val value = System.getenv(env)
        if (!value.isNullOrBlank()) {
            setProperty(key, value)
        }
    }
    fromEnv("storeFile", "FOLIO_STORE_FILE")
    fromEnv("storePassword", "FOLIO_STORE_PASSWORD")
    fromEnv("keyAlias", "FOLIO_KEY_ALIAS")
    fromEnv("keyPassword", "FOLIO_KEY_PASSWORD")
    if (getProperty("keyAlias").isNullOrBlank()) {
        setProperty("keyAlias", "folio")
    }
}

val releaseStoreFile: File? = releaseSigningProps.getProperty("storeFile")?.let { path ->
    val file = File(path)
    if (file.isAbsolute) file else rootProject.file(path)
}

val canSignRelease: Boolean =
    releaseStoreFile?.isFile == true &&
        !releaseSigningProps.getProperty("storePassword").isNullOrBlank() &&
        !releaseSigningProps.getProperty("keyPassword").isNullOrBlank()

android {
    namespace = "com.folio.hermeslive"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.folio.hermeslive"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningProps.getProperty("storePassword")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // The S23 is arm64; the release APK carries only that WebRTC build.
            ndk { abiFilters += "arm64-v8a" }
        }
        debug {
            isMinifyEnabled = false
            // x86_64 for the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":hermes"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.profileinstaller)
    // WebRTC for the GPT-Live peer connection (Google's libwebrtc, prebuilt by Stream). Inline:
    // only :live uses it, and Folio builds :hermes against its own copy of the catalog.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        doFirst {
            check(canSignRelease) {
                "Release builds must be signed. Copy keystore.properties.example to keystore.properties, or set FOLIO_STORE_FILE, FOLIO_STORE_PASSWORD, and FOLIO_KEY_PASSWORD."
            }
        }
    }
}
