package com.morgana.client.net

/**
 * A server to connect to straight from the launch address or command line, optionally with a one-time login ticket
 * (see the server's `dev-ticket` tool): the client then skips the realm browser and password screen and enters the world.
 * Built for automation, such as an LLM driving the client, where no one types a password.
 *
 * Browser: `?connect=host:port&tls=1&ticket=dev_...` (`connect` defaults to the page's own server, `tls` to the page's scheme).
 * Desktop: `--connect host:port [--tls] [--ticket dev_...]`, the ticket also from the `MORGANA_TICKET` environment variable.
 */
data class DirectConnect(val host: String, val port: Int, val tls: Boolean, val ticket: String?) {
    /** The same, as the saved session the client resumes at launch (a ticket is redeemed like a resume token). */
    fun asResumeInfo(): ResumeInfo? = ticket?.let { ResumeInfo(host, port, tls, null, it) }

    companion object {
        /** Reads a page address's query string; null when it names neither a server nor a ticket. */
        fun fromQuery(search: String, defaultHost: String, defaultPort: Int, defaultTls: Boolean): DirectConnect? {
            val params = search.removePrefix("?").split('&').mapNotNull { pair ->
                pair.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { it[0] to decode(it[1]) }
            }.toMap()
            return build(params["connect"], params["tls"], params["ticket"], defaultHost, defaultPort, defaultTls)
        }

        /** Reads command-line arguments (`--connect`, `--tls`, `--ticket`); [env] supplies `MORGANA_TICKET`. */
        fun fromArgs(args: Array<String>, env: (String) -> String?, defaultHost: String, defaultPort: Int): DirectConnect? {
            fun value(flag: String): String? =
                args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.takeIf { v -> !v.startsWith("--") } }
            val tls = if ("--tls" in args) "1" else null
            return build(value("--connect"), tls, value("--ticket") ?: env("MORGANA_TICKET"), defaultHost, defaultPort, false)
        }

        private fun build(connect: String?, tls: String?, ticket: String?, defaultHost: String, defaultPort: Int, defaultTls: Boolean): DirectConnect? {
            val cleanTicket = ticket?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }
            if (connect.isNullOrBlank() && cleanTicket == null) return null
            var host = defaultHost
            var port = defaultPort
            if (!connect.isNullOrBlank()) {
                val c = connect.trim()
                val colon = c.lastIndexOf(':')
                if (colon > 0 && c.substring(colon + 1).toIntOrNull() != null) {
                    host = c.substring(0, colon)
                    port = c.substring(colon + 1).toInt()
                } else {
                    host = c
                }
            }
            if (host.isBlank() || port !in 1..65535) return null
            val useTls = when (tls?.lowercase()) {
                "1", "true", "on", "yes" -> true
                "0", "false", "off", "no" -> false
                else -> defaultTls
            }
            return DirectConnect(host, port, useTls, cleanTicket)
        }

        private fun decode(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                val hex = if (ch == '%' && i + 2 < s.length + 1 && i + 3 <= s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    out.append(hex.toChar())
                    i += 3
                } else {
                    out.append(if (ch == '+') ' ' else ch)
                    i++
                }
            }
            return out.toString()
        }
    }
}
