package com.morgana.client.web

import com.badlogic.gdx.Gdx
import com.morgana.client.net.DeviceFeatures
import org.teavm.jso.JSBody

/** Buzzes the phone for [millis] ms where the browser allows it (Android Chrome; iOS Safari has no vibration API). */
@JSBody(params = ["millis"], script = "try { if (navigator.vibrate) navigator.vibrate(millis); } catch (e) {}")
private external fun jsVibrate(millis: Int)

/**
 * Asks the browser to keep the screen on (Screen Wake Lock API), or releases it. The browser drops the lock whenever the
 * page is hidden, so a function on `window` lets the visibility listener (see [installBrowserLifecycle]) take it again
 * when the player returns. Everything is guarded: unsupported browsers, insecure pages and refusals just mean the screen
 * may dim as usual.
 */
@JSBody(
    params = ["on"],
    script = """
        window.__morganaWakeWanted = !!on;
        window.__morganaAcquireWake = window.__morganaAcquireWake || function () {
            try {
                if (!window.__morganaWakeWanted || !navigator.wakeLock || document.visibilityState !== 'visible' || window.__morganaWake) return;
                navigator.wakeLock.request('screen').then(function (lock) {
                    if (!window.__morganaWakeWanted) { try { lock.release(); } catch (e) {} return; }
                    window.__morganaWake = lock;
                    lock.addEventListener('release', function () { if (window.__morganaWake === lock) window.__morganaWake = null; });
                }).catch(function () {});
            } catch (e) {}
        };
        if (on) {
            window.__morganaAcquireWake();
        } else if (window.__morganaWake) {
            try { window.__morganaWake.release(); } catch (e) {}
            window.__morganaWake = null;
        }
    """
)
private external fun jsKeepScreenOn(on: Boolean)

/**
 * A full-screen message with a Reload button, drawn as plain HTML because it is only used when the game's own frame loop is
 * failing and cannot be trusted to draw anything. Reloading resumes the character (the resume token is stored).
 */
@JSBody(
    params = ["message"],
    script = """
        if (document.getElementById('morgana-fatal')) return;
        var box = document.createElement('div');
        box.id = 'morgana-fatal';
        box.setAttribute('role', 'alertdialog');
        box.style.cssText = 'position:fixed;inset:0;z-index:30000;display:flex;flex-direction:column;align-items:center;justify-content:center;'
            + 'gap:14px;padding:24px;box-sizing:border-box;background:rgba(10,8,7,0.94);color:#f5ede2;font:16px sans-serif;text-align:center;';
        var title = document.createElement('div');
        title.textContent = 'Something went wrong';
        title.style.cssText = 'font:bold 22px sans-serif;color:#f0a04b;';
        var body = document.createElement('div');
        body.textContent = 'The game hit a problem and had to stop drawing. Reloading will take you back to your character.';
        body.style.cssText = 'max-width:420px;';
        var detail = document.createElement('div');
        detail.textContent = message;
        detail.style.cssText = 'max-width:420px;font:12px monospace;opacity:0.6;word-break:break-word;';
        var button = document.createElement('button');
        button.textContent = 'Reload';
        button.style.cssText = 'font:bold 18px sans-serif;padding:12px 28px;border-radius:8px;border:0;background:#f0a04b;color:#1a1512;touch-action:manipulation;';
        button.onclick = function () { window.location.reload(); };
        box.appendChild(title); box.appendChild(body); box.appendChild(detail); box.appendChild(button);
        document.body.appendChild(box);
    """
)
private external fun jsShowFatal(message: String)

/** The browser's implementation of [DeviceFeatures]. */
class BrowserDeviceFeatures : DeviceFeatures {
    override fun vibrate(millis: Int) = jsVibrate(millis)

    override fun keepScreenOn(on: Boolean) = jsKeepScreenOn(on)

    override fun showFatalError(message: String): Boolean {
        jsShowFatal(message)
        return true
    }
}

/** What the page tells the game about its own lifecycle (see [installBrowserLifecycle]). */
class BrowserLifecycleListener(
    val onForeground: () -> Unit,
    val onNetworkOnline: () -> Unit,
    val onNetworkOffline: () -> Unit,
    val onHidden: () -> Unit
)

@JSBody(
    params = ["onForeground", "onOnline", "onOffline", "onHidden"],
    script = """
        var foreground = function () {
            onForeground();
            if (window.__morganaAcquireWake) window.__morganaAcquireWake();
        };
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') foreground();
        });
        // pageshow also fires when the browser restores the page from its back/forward cache (sockets are gone by then).
        window.addEventListener('pageshow', foreground);
        window.addEventListener('online', function () { onOnline(); });
        window.addEventListener('offline', function () { onOffline(); });
        // Leaving or freezing the page: close the socket cleanly so the server need not wait out its idle timeout.
        window.addEventListener('pagehide', function () { onHidden(); });
    """
)
private external fun jsInstallLifecycle(onForeground: SignalCallback, onOnline: SignalCallback, onOffline: SignalCallback, onHidden: SignalCallback)

/**
 * Hooks the page's visibility, network and unload events to the game. Browser events arrive outside the game loop, so each
 * is handed to it with `postRunnable` and applies between frames.
 */
fun installBrowserLifecycle(listener: BrowserLifecycleListener) {
    fun later(action: () -> Unit) = SignalCallback { Gdx.app?.postRunnable(action) ?: action() }
    jsInstallLifecycle(
        later(listener.onForeground),
        later(listener.onNetworkOnline),
        later(listener.onNetworkOffline),
        later(listener.onHidden)
    )
}

/**
 * Makes the page installable and steadier on phones:
 *  - a web app manifest, theme colour and Apple "web app" tags, so "Add to Home Screen" opens the game fullscreen
 *    (no address bar, no browser chrome stealing the edges of the screen);
 *  - the service worker (`sw.js`, see client-web/pwa) that keeps the page's own files for flaky connections, registered only
 *    where the browser allows it (HTTPS or localhost);
 *  - re-sizing after a rotation: browsers report the new window size a moment late, so a resize is re-sent twice;
 *  - a one-time "rotate your phone" hint when a touch device starts in portrait.
 * Nothing here is required: every step is guarded, and a page without the pwa files just skips them.
 */
@JSBody(
    params = ["touch"],
    script = """
        function add(tag, attrs) {
            var el = document.createElement(tag);
            for (var k in attrs) el.setAttribute(k, attrs[k]);
            document.head.appendChild(el);
        }
        try {
            add('link', { rel: 'manifest', href: 'manifest.webmanifest' });
            add('meta', { name: 'theme-color', content: '#1a1512' });
            add('meta', { name: 'mobile-web-app-capable', content: 'yes' });
            add('meta', { name: 'apple-mobile-web-app-capable', content: 'yes' });
            add('meta', { name: 'apple-mobile-web-app-status-bar-style', content: 'black' });
            add('meta', { name: 'apple-mobile-web-app-title', content: 'Morgana' });
            add('link', { rel: 'apple-touch-icon', href: 'icons/icon-192.png' });
        } catch (e) {}
        try {
            if ('serviceWorker' in navigator && (window.isSecureContext || location.hostname === 'localhost')) {
                // main() already runs inside the page's load event, so a 'load' listener added now would never fire.
                navigator.serviceWorker.register('sw.js').catch(function () {});
            }
        } catch (e) {}
        var nudge = function () {
            [150, 500].forEach(function (ms) { setTimeout(function () { window.dispatchEvent(new Event('resize')); }, ms); });
        };
        window.addEventListener('orientationchange', nudge);
        if (screen.orientation && screen.orientation.addEventListener) screen.orientation.addEventListener('change', nudge);
        try {
            if (touch && window.innerHeight > window.innerWidth && window.innerWidth < 700 && !sessionStorage.getItem('morgana.rotateHint')) {
                sessionStorage.setItem('morgana.rotateHint', '1');
                var hint = document.createElement('div');
                hint.textContent = 'Tip: turn your phone sideways for more room to play.';
                hint.style.cssText = 'position:fixed;left:50%;bottom:24px;transform:translateX(-50%);z-index:9000;max-width:80%;'
                    + 'padding:10px 16px;border-radius:8px;background:rgba(26,21,18,0.95);border:1px solid #f0a04b;color:#f5ede2;'
                    + 'font:14px sans-serif;text-align:center;pointer-events:none;';
                document.body.appendChild(hint);
                setTimeout(function () { if (hint.parentNode) hint.parentNode.removeChild(hint); }, 7000);
            }
        } catch (e) {}
    """
)
external fun installPwaSupport(touch: Boolean)
