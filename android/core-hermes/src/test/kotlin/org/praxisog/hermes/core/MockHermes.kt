package org.praxisog.hermes.core

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.Socket

/**
 * Starts and owns the mock-hermes fixture replay server for the test JVM.
 *
 * Started here rather than from a Gradle BuildService so that the configuration cache stays
 * usable and so the tests also run from an IDE. One process per JVM, torn down by a shutdown
 * hook. Gradle supplies the script path, port and key as system properties; see
 * `core-hermes/build.gradle.kts`.
 *
 * The key is a fixed test value, never a real Hermes key.
 */
internal object MockHermes {
    val port: Int = System.getProperty("mockHermes.port")?.toInt() ?: 8646
    val key: String = System.getProperty("mockHermes.key") ?: "contract-test-key"

    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** The session id the recorded fixtures are keyed to. */
    const val FIXTURE_SESSION_ID = "fixture-id-1"

    private val process: Process by lazy { start() }

    private fun start(): Process {
        val scriptPath = System.getProperty("mockHermes.script")
            ?: error("mockHermes.script not set; run via Gradle or set it explicitly")
        val script = File(scriptPath)
        require(script.isFile) { "mock-hermes server not found at $scriptPath" }

        val builder = ProcessBuilder("python3", script.absolutePath)
            .directory(script.parentFile)
            .redirectErrorStream(true)
        builder.environment()["MOCK_HERMES_PORT"] = port.toString()
        builder.environment()["MOCK_HERMES_KEY"] = key
        val started = builder.start()
        Runtime.getRuntime().addShutdownHook(Thread { started.destroyForcibly() })

        // Poll the port instead of sleeping a guessed amount.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) {
                error("mock-hermes exited early:\n" + started.inputStream.readBytes().decodeToString())
            }
            try {
                Socket("127.0.0.1", port).close()
                return started
            } catch (_: IOException) {
                Thread.sleep(100)
            }
        }
        started.destroyForcibly()
        error("mock-hermes did not open port $port within 20s")
    }

    /** Ensures the server is running. Called by every factory below. */
    private fun ensureRunning() {
        check(process.isAlive) { "mock-hermes died during the test run" }
    }

    /**
     * An OkHttp client that injects mock-hermes' `X-Mock-Fail` control header on every
     * request, so a test can reach a server-side failure without the production client
     * exposing any way to ask for one.
     *
     * Built by extending [HermesClient.defaultHttpClient] rather than from a fresh builder,
     * so it differs from the production client by exactly one header. A hand-rolled builder
     * here silently dropped `followRedirects(false)` and made the redirect test pass through
     * to real DNS — a test that configures its own transport is testing the wrong transport.
     */
    fun faultingClient(fault: String): OkHttpClient = HermesClient.defaultHttpClient()
        .newBuilder()
        .addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-Mock-Fail", fault).build())
        })
        .build()

    fun client(
        key: String = this.key,
        baseUrl: String = this.baseUrl,
        httpClient: OkHttpClient = HermesClient.defaultHttpClient(),
        sleeper: (Long) -> Unit = { },
    ): HermesClient {
        ensureRunning()
        return HermesClient(baseUrl, { key }, httpClient = httpClient, sleeper = sleeper)
    }
}
