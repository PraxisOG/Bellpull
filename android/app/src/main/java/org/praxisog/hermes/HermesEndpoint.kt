package org.praxisog.hermes

import java.net.URI

internal object HermesEndpoint {
    fun parse(input: String): Result<String> = runCatching {
        val uri = URI(input.trim())
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        require(scheme == "http" || scheme == "https") { "Use an HTTP or HTTPS server URL." }
        require(!host.isNullOrBlank()) { "Enter a server host." }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Enter only the server address and port."
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "Enter only the server address and port."
        }
        require(uri.port in -1..65535 && uri.port != 0) { "Enter a valid port." }
        if (scheme == "http") {
            require(isTailnetHost(host) || host == "localhost" || host == "127.0.0.1") {
                "Plain HTTP is allowed only over Tailscale or loopback."
            }
        }
        URI(scheme, null, host, uri.port, null, null, null).toString()
    }

    private fun isTailnetHost(host: String): Boolean {
        if (host.endsWith(".ts.net")) return true
        val octets = host.split('.')
        if (octets.size != 4) return false
        val parts = octets.map { it.toIntOrNull() ?: return false }
        return parts.all { it in 0..255 } && parts[0] == 100 && parts[1] in 64..127
    }
}
