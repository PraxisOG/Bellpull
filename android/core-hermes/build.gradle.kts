plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core-model"))
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("junit:junit:4.13.2")
}

// Contract tests replay fixtures through mock-hermes rather than hitting a live Hermes, so
// `./gradlew test` needs no manual setup. The server is started by the test JVM itself (see
// MockHermes.kt) rather than by a Gradle BuildService: a service plus a doFirst hook cannot
// be stored in the configuration cache, which this project has enabled, and starting it
// test-side also means the tests run unchanged from an IDE. Gradle only passes the
// coordinates -- all plain strings, so the configuration cache stays clean.
//
// The key is a fixed test value. It is not, and must never be, a real Hermes key.
tasks.withType<Test>().configureEach {
    systemProperty(
        "mockHermes.script",
        rootProject.layout.projectDirectory.file("../mock-hermes/server.py").asFile.absolutePath,
    )
    systemProperty("mockHermes.port", "8646")
    systemProperty("mockHermes.key", "contract-test-key")
}
