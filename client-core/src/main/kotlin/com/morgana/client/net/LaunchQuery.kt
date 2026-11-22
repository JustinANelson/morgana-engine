package com.morgana.client.net

/**
 * Reads the server port a browser page asks for in its address, e.g. `http://127.0.0.1:8090/?port=7022`, so a link can point
 * the login window at a server on a non-standard port. Returns null when there is no valid port (1..65535) in [search].
 */
fun portFromQuery(search: String): Int? =
    search.removePrefix("?").split('&')
        .mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "port" }?.get(1)?.trim()?.toIntOrNull() }
        .firstOrNull { it in 1..65535 }

/** Port of the TeaVM development server (`gdx_teavm_web_js_run`), which serves pages but is not a game server. */
const val TEAVM_DEV_SERVER_PORT = 8080

/**
 * The game-server port a browser page connects to when its address names none (`?port=`). A page served by the game
 * server itself (`http://my-pc:7002/`, the usual way to play from a phone) talks to the port it came from; an https page
 * with no port (behind `tailscale serve` or another proxy) to 443; the development server's page, or a page with no
 * port at all, to [fallback], the server's usual WebSocket port.
 */
fun webDefaultPort(pagePort: String, secure: Boolean, fallback: Int): Int {
    val port = pagePort.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    return when {
        port == null -> if (secure) 443 else fallback
        port == TEAVM_DEV_SERVER_PORT -> fallback
        else -> port
    }
}
