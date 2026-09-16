package org.praxisog.hermes

import java.io.File
import java.io.IOException
import java.net.Socket

/**
 * Starts mock-hermes for the app module's tests.
 *
 * A near-copy of core-hermes' launcher. Sharing it would mean either a `java-test-fixtures`
 * artifact or publishing test code as main code; for forty lines with no logic worth
 * diverging, a second copy is the smaller cost. If a third module ever needs it, promote it.
 */
internal object MockHermesLauncher {
    val port: Int = System.getProperty("mockHermes.port")?.toInt() ?: 8647
    val key: String = System.getProperty("mockHermes.key") ?: "app-store-test-key"
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** The session id the recorded fixtures are keyed to. */
    const val FIXTURE_SESSION_ID = "fixture-id-1"

    private val process: Process by lazy { start(port, delaySeconds = null) }

    /**
     * A second instance that spaces SSE events out. With no delay the whole fixture stream
     * lands in microseconds, so "arrives incrementally" is not observable — the test would
     * pass or fail on scheduler luck rather than on behaviour.
     */
    private val slowProcess: Process by lazy { start(slowPort, delaySeconds = "0.08") }

    val slowPort: Int get() = port + 1
    val slowBaseUrl: String get() = "http://127.0.0.1:$slowPort"

    fun ensureRunning() {
        check(process.isAlive) { "mock-hermes died during the test run" }
    }

    fun ensureSlowRunning() {
        check(slowProcess.isAlive) { "slow mock-hermes died during the test run" }
    }

    private fun start(port: Int, delaySeconds: String?): Process {
        val script = File(
            System.getProperty("mockHermes.script")
                ?: error("mockHermes.script not set; run via Gradle")
        )
        require(script.isFile) { "mock-hermes server not found at $script" }
        val builder = ProcessBuilder("python3", script.absolutePath)
            .directory(script.parentFile)
            .redirectErrorStream(true)
        builder.environment()["MOCK_HERMES_PORT"] = port.toString()
        builder.environment()["MOCK_HERMES_KEY"] = key
        delaySeconds?.let { builder.environment()["MOCK_HERMES_SSE_DELAY"] = it }
        val started = builder.start()
        Runtime.getRuntime().addShutdownHook(Thread { started.destroyForcibly() })
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
}
