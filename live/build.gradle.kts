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

val diagnosticsUrl: String =
    (System.getenv("FOLIO_DIAGNOSTICS_URL") ?: providers.gradleProperty("folio.diagnosticsUrl").orNull).orEmpty().trim()

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
        versionCode = 7
        versionName = "1.4.0"
        // Instrumented tests (S-C audio and STT, on the emulator). Long corpus benches run only
        // with the instrumentation argument bench=true (see src/androidTest/README.md).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // Default diagnostics endpoint (OTLP/HTTP). Only release CI sets FOLIO_DIAGNOSTICS_URL
            // (a repo variable); local and fork builds get "" and send nothing. Never commit a URL.
            buildConfigField("String", "DIAGNOSTICS_URL", "\"${diagnosticsUrl.replace("\"", "")}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-sherpa.pro",
            )
            // The S23 is arm64; the release APK carries only that WebRTC and sherpa-onnx build.
            ndk { abiFilters += "arm64-v8a" }
        }
        debug {
            // Debug builds and unit tests never send (even when CI has the variable).
            buildConfigField("String", "DIAGNOSTICS_URL", "\"\"")
            isMinifyEnabled = false
            // x86_64 for the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        // Release-like (R8, shrink, the release keep rules) but debug-signed, with x86_64, beside
        // the debug app (`.staging`): proves R8 + sherpa's JNI + the cascade on the emulator.
        // Never shipped; no diagnostics; its BuildHooks only add the fake Hermes address.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "DIAGNOSTICS_URL", "\"\"")
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    sourceSets {
        // src/hooks: the corpus audio source and the capturing output (test input, never in release).
        getByName("debug").kotlin.srcDir("src/hooks/java")
        getByName("staging").kotlin.srcDir("src/hooks/java")
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
        jniLibs {
            // sherpa-onnx's C and C++ API libraries: only libsherpa-onnx-jni.so (+ onnxruntime) is
            // loaded from Kotlin, and it does not link them (readelf NEEDED, v1.13.8).
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
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
    // On-device speech-to-text (S-C: Silero VAD, streaming Zipformer, offline Parakeet) and the
    // tar.bz2 model archives ModelManager unpacks. Inline, like WebRTC: only :live uses them.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

afterEvaluate {
    // A plain Boolean, so the configuration cache stores no script object.
    val signable = canSignRelease
    tasks.named("assembleRelease").configure {
        doFirst {
            check(signable) {
                "Release builds must be signed. Copy keystore.properties.example to keystore.properties, or set FOLIO_STORE_FILE, FOLIO_STORE_PASSWORD, and FOLIO_KEY_PASSWORD."
            }
        }
    }
}

// Unit tests in parallel JVMs (test-shape plan): half the cores, at most 8. forkEvery stays unset,
// so Robolectric reuses its sandbox within a fork.
tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)
    maxHeapSize = "1g"
}

// The screenshot renders are not tests: they leave Tier 0 (`test`) and run on their own when the
// UI changes (`./gradlew :live:renderScreenshots`). `SmokeRenderTest` keeps one fast render in Tier 0.
val screenshotRenders = listOf("*.ScreenshotRender", "*.CascadeScreenshotRender", "*.MeetingScreenshotRender")
tasks.withType<Test>().matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" || it.name == "testStagingUnitTest" }
    .configureEach { filter { screenshotRenders.forEach { excludeTestsMatching(it) } } }

tasks.register<Test>("renderScreenshots") {
    description = "Renders the screens to PNGs (build/screenshots; CASCADE_SCREENSHOTS_DIR for the committed cascade copies)."
    group = "verification"
    // The unit tests' own classes and classpath (their build dependencies, not the test task itself).
    val unit = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unit.testClassesDirs
    classpath = unit.classpath
    filter { screenshotRenders.forEach { includeTestsMatching(it) } }
    // Robolectric reads the merged resources and manifest from the same config as the unit tests.
    jvmArgumentProviders.addAll(unit.jvmArgumentProviders)
    systemProperties(unit.systemProperties)
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
}
