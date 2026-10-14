// Fake Hermes self-tests. The server itself is Python 3 (stdlib only, see README.md and run.sh);
// this module has no main code. Its JVM tests start the server as a subprocess and drive it with
// the real :hermes client classes (HermesAuth, HermesGateway, HermesConversation,
// GatewaySessionRecovery). Nothing here ships in an APK.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.folio.fakehermes"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
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
    testImplementation(project(":hermes"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}

// The tests run the Python server from this directory: re-run them when it or a scenario changes.
val serverDir = layout.projectDirectory
tasks.withType<Test>().configureEach {
    inputs.dir(serverDir.dir("fakehermes")).withPropertyName("fakeHermesServer")
    inputs.dir(serverDir.dir("scenarios")).withPropertyName("fakeHermesScenarios")
    inputs.file(serverDir.file("fake_hermes.py")).withPropertyName("fakeHermesMain")
    systemProperty("fakehermes.dir", serverDir.asFile.absolutePath)
}

// Unit tests in parallel JVMs (test-shape plan): half the cores, at most 8.
tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)
    maxHeapSize = "1g"
}
