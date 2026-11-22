package com.morgana.client.web

import com.badlogic.gdx.Gdx
import com.morgana.client.ui.TextEntryBridge
import org.teavm.jso.JSBody
import org.teavm.jso.JSFunctor
import org.teavm.jso.JSObject

@JSFunctor
fun interface TextCallback : JSObject {
    fun call(value: String)
}

@JSFunctor
fun interface SignalCallback : JSObject {
    fun call()
}

/**
 * The phone keyboard for the browser client: gdx-teavm cannot raise it (its `setOnscreenKeyboardVisible` is empty), and
 * a phone only shows a keyboard for a real HTML input. While a game text field has focus on a touch screen, a bar
 * across the top of the page holds an `<input>` mirroring it, with a Done button. Typing there edits the game field;
 * Enter acts as Enter in the game; Done or tapping away closes it. Android opens the keyboard at once; on iPhone, tap
 * the bar's box if the keyboard did not open (iOS only allows it from a direct tap).
 */
class BrowserTextEntry : TextEntryBridge {
    override fun open(text: String, hint: String, password: Boolean, onChange: (String) -> Unit, onEnter: () -> Unit, onClose: () -> Unit) {
        // Browser events arrive outside the game loop; hand them to it so they apply between frames, in order.
        fun later(action: () -> Unit) = Gdx.app?.postRunnable(action) ?: action()
        showBar(
            text, hint, password,
            TextCallback { v -> later { onChange(v) } },
            SignalCallback { later(onEnter) },
            SignalCallback { later(onClose) }
        )
    }

    override fun close() = hideBar()
}

@JSBody(
    params = ["text", "hint", "password", "onChange", "onEnter", "onClose"],
    script = """
        var bar = document.getElementById('morgana-text-entry');
        var input, done;
        if (!bar) {
            bar = document.createElement('div');
            bar.id = 'morgana-text-entry';
            bar.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:10000;display:flex;gap:8px;align-items:center;'
                + 'padding:8px max(8px, env(safe-area-inset-right)) 8px max(8px, env(safe-area-inset-left));'
                + 'background:rgba(26,21,18,0.97);border-bottom:2px solid #f0a04b;box-sizing:border-box;';
            input = document.createElement('input');
            input.id = 'morgana-text-input';
            input.setAttribute('autocomplete', 'off');
            input.setAttribute('autocapitalize', 'off');
            input.setAttribute('autocorrect', 'off');
            input.setAttribute('spellcheck', 'false');
            input.setAttribute('enterkeyhint', 'go');
            // 16 px or more stops iPhone Safari zooming the page when the box is tapped.
            input.style.cssText = 'flex:1;min-width:0;font:16px sans-serif;padding:10px;border-radius:6px;'
                + 'border:1px solid #4f4338;background:#f5ede2;color:#1a1512;-webkit-user-select:text;user-select:text;touch-action:manipulation;';
            done = document.createElement('button');
            done.id = 'morgana-text-done';
            done.textContent = 'Done';
            done.style.cssText = 'font:bold 16px sans-serif;padding:10px 14px;border-radius:6px;border:0;background:#f0a04b;color:#1a1512;touch-action:manipulation;';
            bar.appendChild(input);
            bar.appendChild(done);
            document.body.appendChild(bar);
        } else {
            input = document.getElementById('morgana-text-input');
            done = document.getElementById('morgana-text-done');
        }
        input.type = password ? 'password' : 'text';
        input.placeholder = hint || 'Type here';
        input.value = text;
        var syncValue = function () { onChange(input.value); };
        input.oninput = syncValue;
        input.oncompositionupdate = syncValue;
        input.oncompositionend = syncValue;
        input.onchange = syncValue;
        input.onkeyup = function (e) { e.stopPropagation(); syncValue(); };
        input.onkeypress = function (e) { e.stopPropagation(); };
        input.onkeydown = function (e) {
            // Stop propagation so gdx-teavm's document listener does not preventDefault() on Backspace or trigger hotkeys.
            e.stopPropagation();
            if (e.key === 'Enter') { e.preventDefault(); syncValue(); onEnter(); }
        };
        // Tapping away closes the bar, unless the tap moved straight to the Done button or back into the box.
        input.onblur = function () {
            syncValue();
            setTimeout(function () {
                if (document.getElementById('morgana-text-entry') && document.activeElement !== input) {
                    syncValue();
                    onClose();
                }
            }, 250);
        };
        done.onpointerdown = function (e) { e.preventDefault(); };
        done.onclick = function (e) { e.preventDefault(); syncValue(); onClose(); };
        input.focus();
        try { input.setSelectionRange(input.value.length, input.value.length); } catch (err) {}
    """
)
private external fun showBar(text: String, hint: String, password: Boolean, onChange: TextCallback, onEnter: SignalCallback, onClose: SignalCallback)

@JSBody(
    script = """
        var bar = document.getElementById('morgana-text-entry');
        if (!bar) return;
        var input = document.getElementById('morgana-text-input');
        if (input) { input.onblur = null; input.blur(); }
        bar.parentNode.removeChild(bar);
    """
)
private external fun hideBar()
