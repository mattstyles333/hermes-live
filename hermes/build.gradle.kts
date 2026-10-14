plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.folio.hermes"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // The consuming app runs R8; consumer-rules.pro travels with the AAR.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Public API exposes OkHttpClient, JsonObject and Flow, so these are api, not implementation.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Unit tests in parallel JVMs (test-shape plan): half the cores, at most 8.
tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)
    maxHeapSize = "1g"
    // Record and update modes (LiveJevEvalTest, RecordedJevTest) must run, not come up to date.
    // Only these switches are inputs; the OpenRouter key never is.
    inputs.property("recordJev", providers.environmentVariable("RECORD_JEV").orElse(""))
    inputs.property("updateJevEffective", providers.environmentVariable("UPDATE_JEV_EFFECTIVE").orElse(""))
    // The opt-in live tests' secrets (OpenRouter key, telemetry URL) never go into a
    // configuration-cache entry: dropped from the task's environment here, read when it runs.
    val secrets = listOf("OPENROUTER_API_KEY", "TELEMETRY_LIVE_URL")
    secrets.forEach { environment.remove(it) }
    doFirst {
        val test = this as Test
        secrets.forEach { name -> System.getenv(name)?.let { test.environment(name, it) } }
    }
}
