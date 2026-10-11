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
            "Do not include credentials, a query, or a fragment in the server URL."
        }
        val path = uri.rawPath.orEmpty().removeSuffix("/")
        require(path.isEmpty() || Regex("/p/[a-z0-9][a-z0-9_-]{0,63}").matches(path)) {
            "Use a root server URL or a native Hermes /p/profile URL."
        }
        require(uri.port in -1..65535 && uri.port != 0) { "Enter a valid port." }
        if (scheme == "http") {
            require(isTailnetHost(host) || host == "localhost" || host == "127.0.0.1") {
                "Plain HTTP is allowed only over Tailscale or loopback."
            }
        }
        URI(scheme, null, host, uri.port, path.ifEmpty { null }, null, null).toString()
    }

    private fun isTailnetHost(host: String): Boolean {
        if (host.endsWith(".ts.net")) return true
        val octets = host.split('.')
        if (octets.size != 4) return false
        val parts = octets.map { it.toIntOrNull() ?: return false }
        return parts.all { it in 0..255 } && parts[0] == 100 && parts[1] in 64..127
    }
}
