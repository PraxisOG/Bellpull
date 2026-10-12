import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

// Release signing is configured from an untracked file so no keystore or password ever
// reaches the repo. Create android/keystore.properties (gitignored) with:
//
//   storeFile=/absolute/path/to/hermes-release.jks
//   storePassword=...
//   keyAlias=hermes
//   keyPassword=...
//
// Absent that file, assembleRelease still builds -- it just produces an unsigned APK, which
// is the right failure mode for CI and for anyone cloning this.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile")?.let { File(it).exists() } == true

android {
    namespace = "org.praxisog.hermes"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }

    defaultConfig {
        // Device fixtures use a separate sandbox; personal profiles are never loaded.
        applicationId = if (providers.gradleProperty("bellpullDeviceFixture").orNull == "true")
            "org.praxisog.hermes.fixture" else "org.praxisog.hermes"
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
        targetSdk = 37
        versionCode = 18
        versionName = "0.6.0-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
            // R8 stays OFF deliberately. Glance, kotlinx-serialization and OkHttp all need
            // keep rules, and a shrunk build that has never run on the device is a worse bet
            // than a slightly larger one that has. Turn it on only with a real install to
            // verify against -- an R8 failure here looks like a widget that never appears or
            // a session list that decodes to nothing.
            isMinifyEnabled = false
            isShrinkResources = false
        }
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
                // Pixel 10 Pro Fold geometry, to match the real target device. The system
                // image is still AOSP ATD at API 34, not the Fold's API 37, so this catches
                // layout and Activity-level problems but NOT Android 17 behaviour -- and a
                // GMD emulator cannot fold, so it gives the unfolded geometry, never the
                // transition. On-device testing is still required.
                create("aospAtdApi34") {
                    device = "Pixel 10 Pro Fold"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                    // Pinned explicitly: AGP warns that the default flips to "arm64-v8a" in
                    // AGP 10.0, and this ATD image cannot do NDK translation, so that flip
                    // would break the task rather than just slow it down.
                    testedAbi = "x86_64"
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
    // Approved Markdown parser; Compose owns rendering and link handling.
    implementation("org.commonmark:commonmark:0.30.1")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.1")
    // User-approved local CPU speech SDK (2026-10-11). Bellpull owns capture/downloads.
    implementation("ai.moonshine:moonshine-voice:0.1.5") {
        exclude(group = "com.google.android.material")
        exclude(group = "androidx.work")
    }
    // Already on the classpath transitively via Compose; declared so the version is ours
    // rather than an accident of whatever compose-bom happens to pull in.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Glance is the AndroidX way to build an app widget in Compose. AndroidX, not a Play
    // library, so it does not put a Google surface on a device that has none. 1.2.0 is the
    // latest stable; 1.3.0 is still alpha.
    implementation("androidx.glance:glance-appwidget:1.2.0")
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

    // Instrumentation tests. These exist for what Roborazzi cannot see: Roborazzi renders
    // composables outside an Activity, so Activity-level chrome -- the stray action bar --
    // is invisible to it by construction.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
