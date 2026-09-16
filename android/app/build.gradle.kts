plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "org.praxisog.hermes"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.praxisog.hermes"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // HermesStoreTest drives a real client against mock-hermes, so it needs the same
            // coordinates core-hermes' contract tests use. Plain strings, so the
            // configuration cache stays clean.
            it.systemProperty(
                "mockHermes.script",
                rootProject.layout.projectDirectory.file("../mock-hermes/server.py").asFile.absolutePath,
            )
            it.systemProperty("mockHermes.port", "8647")
            it.systemProperty("mockHermes.key", "app-store-test-key")
        }
        managedDevices {
            localDevices {
                create("aospAtdApi34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation(project(":core-hermes"))
    // Already on the classpath transitively via Compose; declared so the version is ours
    // rather than an accident of whatever compose-bom happens to pull in.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    // Test-only. core-hermes keeps OkHttp as `implementation` so the app's production code
    // cannot see it; HermesStoreTest needs the type to inject mock-hermes' X-Mock-Fail header
    // without the production client exposing any way to request a failure.
    testImplementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.63.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.63.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
