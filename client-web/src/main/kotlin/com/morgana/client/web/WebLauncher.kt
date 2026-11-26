package com.morgana.client.web

import com.github.xpenatan.gdx.teavm.backends.web.WebApplication
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration
import com.morgana.client.MorganaGame
import com.morgana.client.net.portFromQuery
import com.morgana.client.render.applyList
import com.morgana.client.preview.DemoLocalWorldHost
import com.morgana.client.realms.DEFAULT_CLASSES
import com.morgana.client.realms.InMemoryRealmDirectoryClient
import com.morgana.client.preview.fakeWorldsFromQuery
import com.morgana.client.realms.defaultBundledDirectory
import com.morgana.client.preview.demoRealmDirectory
import com.morgana.client.preview.fakeCharactersFromQuery
import com.morgana.client.preview.fakeRealmsFromQuery
import com.morgana.client.preview.demoCharacterList
import com.morgana.protocol.realms.RealmDirectoryDocument
import com.morgana.protocol.realms.RealmSummary
import org.teavm.jso.JSBody
import org.teavm.jso.JSFunctor
import org.teavm.jso.JSObject
import org.teavm.jso.browser.Window

/** Default port of the server's WebSocket listener (`MORGANA_WS_PORT`). */
private const val DEFAULT_WEB_PORT = 7002

private const val TAILSCALE_FUNNEL_HOST = "legion-5-laptop.tail4e16.ts.net"
private const val TAILSCALE_FUNNEL_PORT = 443

private fun webBundledDirectory(pageHost: String, port: Int, secure: Boolean): RealmDirectoryDocument {
    val base = defaultBundledDirectory(pageHost, port, tls = secure)
    val funnelRealm = RealmSummary(
        id = "tailscale-funnel",
        name = "Tailscale Funnel test realm",
        description = "Live test realm hosted via Tailscale Funnel.",
        host = TAILSCALE_FUNNEL_HOST,
        port = TAILSCALE_FUNNEL_PORT,
        tls = true,
        fingerprint = null,
        gameTitle = "",
        engineApi = 1,
        onlinePlayers = 0,
        maxPlayers = 100,
        tags = listOf("roguelike", "tailscale"),
        classes = DEFAULT_CLASSES
    )
    val localIsSameOrigin = pageHost == "127.0.0.1" || pageHost == "localhost" || pageHost.endsWith(".ts.net")
    return if (localIsSameOrigin) {
        base.copy(realms = base.realms + funnelRealm)
    } else {
        base.copy(
            realms = listOf(funnelRealm) + base.realms,
            featuredRealmId = funnelRealm.id
        )
    }
}

/**
 * True when the browser reports a touch screen (or the page is opened with `?touch=1`, to try the touch interface on a desktop). Also prepares the page for touch play: a fixed, non-zoomable viewport,
 * and no browser panning/pull-to-refresh/double-tap-zoom stealing gestures from the game canvas.
 */
@JSBody(script = """
    var touch = ('ontouchstart' in window) || (navigator.maxTouchPoints > 0)
        || (window.matchMedia && window.matchMedia('(pointer: coarse)').matches)
        || /[?&]touch=1(&|$)/.test(location.search);
    var meta = document.querySelector('meta[name=viewport]');
    if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; document.head.appendChild(meta); }
    meta.content = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no';
    var st = document.createElement('style');
    // No viewport-fit=cover: the page then stays inside the phone's safe area (clear of notches and rounded corners), and the
    // black body fills the strips beside it.
    st.textContent = 'html,body{margin:0;height:100%;background:#000;overflow:hidden;overscroll-behavior:none;touch-action:none;'
        + '-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent}'
        + 'canvas{touch-action:none}';
    document.head.appendChild(st);
    return touch;
""")
private external fun prepareTouchPage(): Boolean

/** True when the browser or the operating system asks for less motion (`prefers-reduced-motion`); Reduce motion starts on then. */
@JSBody(script = "return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);")
private external fun prefersReducedMotion(): Boolean

/** Returns the client's state as JSON; the smoke test calls it through `window.__morganaProbe()`. */
@JSFunctor
fun interface ProbeCallback : JSObject {
    fun snapshot(): String
}

@JSBody(params = ["cb"], script = "window.__morganaProbe = function () { return cb(); };")
private external fun exposeProbe(cb: ProbeCallback)

@JSFunctor
fun interface BrowserPngCallback : JSObject {
    fun onDataUrl(dataUrl: String)
}

@JSBody(
    params = ["cb"],
    script = """
        var input = document.createElement('input');
        input.type = 'file';
        input.accept = '.png,image/png';
        input.style.display = 'none';
        input.onchange = function () {
            var file = input.files && input.files[0];
            if (!file) { cb(''); input.remove(); return; }
            var reader = new FileReader();
            reader.onload = function () { cb(String(reader.result || '')); input.remove(); };
            reader.onerror = function () { cb(''); input.remove(); };
            reader.readAsDataURL(file);
        };
        document.body.appendChild(input);
        input.click();
    """
)
private external fun openBrowserPngInput(cb: BrowserPngCallback)

private fun pickBrowserPngFile(onPicked: (ByteArray?) -> Unit) {
    openBrowserPngInput(BrowserPngCallback { dataUrl ->
        val marker = "base64,"
        val idx = dataUrl.indexOf(marker)
        val bytes = if (idx >= 0) com.morgana.protocol.Base64Codec.decodeOrNull(dataUrl.substring(idx + marker.length)) else null
        com.badlogic.gdx.Gdx.app?.postRunnable { onPicked(bytes) } ?: onPicked(bytes)
    })
}

/** Removes the query string from the address bar so a one-time ticket does not stay in the history or get copied around. */
@JSBody(script = "try { history.replaceState(null, '', location.pathname); } catch (e) {}")
private external fun scrubAddressQuery()

/** Browser entry point. The login window defaults to the server that served this page (see [com.morgana.client.net.webDefaultPort]). */
fun main() {
    val location = Window.current().location
    val pageHost = location.hostName.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    val secure = location.protocol == "https:"
    val queryPort = portFromQuery(location.search ?: "")
        ?: com.morgana.client.net.webDefaultPort(location.port ?: "", secure, DEFAULT_WEB_PORT)
    // `?connect=host:port&ticket=dev_...` goes straight to that server and logs in with the one-time ticket (automation).
    val direct = com.morgana.client.net.DirectConnect.fromQuery(location.search ?: "", pageHost, queryPort, secure)
    val port = direct?.port ?: queryPort
    // Read the effects choice before the address is scrubbed: location.search is empty afterwards.
    val fxChoice = com.morgana.client.render.fxFromQuery(location.search ?: "")
    if (direct?.ticket != null) scrubAddressQuery()
    // `?fx=none` / `?fx=lighting,shadows,hd2d` / `?fx=all` picks the experimental visual effects (default: all on).
    com.morgana.client.render.VisualFx.applyList(fxChoice)
    val touch = prepareTouchPage()
    val config = WebApplicationConfiguration().apply {
        // 0 = fill the browser window (the stage resizes with it).
        width = 0
        height = 0
    }
    val game =
        MorganaGame(
            transport = BrowserWebSocketTransport(),
            soundLoader = BrowserSoundLoader(),
            defaultHost = direct?.host ?: pageHost,
            defaultPort = port,
            // `?fakeRealms=200` previews the realm browser with a made-up directory of that size; otherwise the bundled one.
            directoryClient = fakeRealmsFromQuery(Window.current().location.search ?: "")
                ?.let { InMemoryRealmDirectoryClient(demoRealmDirectory(it)) }
                ?: InMemoryRealmDirectoryClient(webBundledDirectory(pageHost, port, secure)),
            // `?fakeWorlds=1` shows the Local Worlds tab with pretend worlds (a browser cannot host a real one).
            localWorlds = if (fakeWorldsFromQuery(Window.current().location.search ?: "")) DemoLocalWorldHost() else null,
            // `?fakeCharacters=2` shows the character screen with that many made-up characters (for screenshots).
            previewCharacters = fakeCharactersFromQuery(Window.current().location.search ?: "")?.let { demoCharacterList(it) },
            touchDevice = touch,
            perRealmStore = BrowserPerRealmStore(),
            resumeStore = BrowserResumeStore(),
            assetDownloader = BrowserAssetDownloader(),
            // Realm art kept in IndexedDB between visits, so unchanged files are not fetched again.
            assetCache = BrowserAssetCache(),
            pickPngFile = ::pickBrowserPngFile,
            textEntry = BrowserTextEntry(),
            systemReducedMotion = prefersReducedMotion(),
            device = BrowserDeviceFeatures(),
            directLogin = direct?.asResumeInfo()
        )
    installPwaSupport(touch)
    installBrowserLifecycle(
        BrowserLifecycleListener(
            onForeground = game::onAppForeground,
            onNetworkOnline = game::onNetworkOnline,
            onNetworkOffline = game::onNetworkOffline,
            onHidden = game::onPageHidden
        )
    )
    exposeProbe(ProbeCallback { game.probeSnapshot() })
    WebApplication(game, config)
}

