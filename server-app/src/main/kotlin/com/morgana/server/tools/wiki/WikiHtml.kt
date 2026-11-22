package com.morgana.server.tools.wiki

/**
 * The look and the small amount of script shared by every page of the game wiki: HTML escaping, a table builder whose
 * columns sort (numbers as numbers) and filter, the page frame with its navigation and search box, and the style sheet.
 * No external files or libraries: a generated site is plain files that open from disk or from the server.
 */
internal object WikiHtml {
    fun esc(text: String): String = buildString(text.length + 8) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    fun link(href: String, text: String): String = "<a href=\"${esc(href)}\">${esc(text)}</a>"

    /** One table cell: [html] is shown, [sort] (a number or text) is what the column sorts by when it differs from the text. */
    class Cell(val html: String, val sort: String? = null, val cls: String = "")

    fun cell(text: String, sort: Number? = null, cls: String = "") = Cell(esc(text), sort?.toString(), cls)
    fun rawCell(html: String, sort: Number? = null, cls: String = "") = Cell(html, sort?.toString(), cls)
    fun num(value: Number, cls: String = "num") = Cell(value.toString(), value.toString(), cls)

    fun table(headers: List<String>, rows: List<List<Cell>>, id: String, filterable: Boolean = true): String = buildString {
        if (filterable && rows.size > 8) {
            append("<input class=\"filter\" type=\"search\" placeholder=\"Filter ${rows.size} rows...\" data-table=\"$id\" aria-label=\"Filter table\">")
        }
        append("<div class=\"scroll\"><table class=\"data\" id=\"$id\"><thead><tr>")
        for (h in headers) append("<th scope=\"col\" tabindex=\"0\">${esc(h)}</th>")
        append("</tr></thead><tbody>")
        for (row in rows) {
            append("<tr>")
            for (c in row) {
                append("<td")
                if (c.cls.isNotEmpty()) append(" class=\"${c.cls}\"")
                if (c.sort != null) append(" data-sort=\"${esc(c.sort)}\"")
                append(">").append(c.html).append("</td>")
            }
            append("</tr>")
        }
        append("</tbody></table></div>")
    }

    /** A two-column "label: value" block for a detail page. Values are HTML. */
    fun facts(rows: List<Pair<String, String>>): String = buildString {
        append("<dl class=\"facts\">")
        for ((k, v) in rows) append("<dt>${esc(k)}</dt><dd>$v</dd>")
        append("</dl>")
    }

    val NAV: List<Pair<String, String>> = listOf(
        "index.html" to "Home", "items.html" to "Items", "monsters.html" to "Monsters", "npcs.html" to "NPCs",
        "spells.html" to "Spells", "quests.html" to "Quests", "recipes.html" to "Recipes", "shops.html" to "Shops",
        "resources.html" to "Resources", "maps.html" to "Maps", "classes.html" to "Classes", "balance.html" to "Balance"
    )

    fun page(title: String, gameName: String, current: String, body: String): String = buildString {
        append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>${esc(title)} - ${esc(gameName)} Wiki</title>")
        append("<link rel=\"stylesheet\" href=\"wiki.css\"></head><body>")
        append("<header><a class=\"brand\" href=\"index.html\">${esc(gameName)} Wiki</a>")
        append("<input id=\"search\" type=\"search\" placeholder=\"Search everything...\" autocomplete=\"off\" aria-label=\"Search the wiki\">")
        append("<div id=\"results\" hidden></div></header>")
        append("<nav>")
        for ((href, label) in NAV) append("<a href=\"$href\"").append(if (href == current) " class=\"here\"" else "").append(">${esc(label)}</a>")
        append("</nav><main><h1>${esc(title)}</h1>$body</main>")
        append("<footer>Generated from the game's data files. Numbers are computed from them; nothing here is typed by hand.</footer>")
        append("<script src=\"search-index.js\"></script><script src=\"wiki.js\"></script></body></html>")
    }

    val CSS = """
:root{--bg:#fafaf7;--fg:#1c1c1a;--muted:#6b6b63;--line:#d9d8d0;--card:#fff;--accent:#8a4b12;--good:#1d7a3a;--warn:#a05a00;--bad:#b3261e}
@media (prefers-color-scheme:dark){:root{--bg:#16161a;--fg:#e8e6df;--muted:#9a988f;--line:#34343a;--card:#1e1e24;--accent:#e0a263;--good:#6cc58a;--warn:#e0a263;--bad:#f2837c}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
header{display:flex;gap:16px;align-items:center;padding:10px 16px;border-bottom:1px solid var(--line);background:var(--card);position:relative;flex-wrap:wrap}
.brand{font-weight:700;font-size:18px;color:var(--accent);text-decoration:none}
#search{flex:1;min-width:180px;max-width:420px;padding:7px 10px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg)}
#results{position:absolute;top:100%;left:16px;right:16px;max-width:560px;background:var(--card);border:1px solid var(--line);border-radius:6px;z-index:5;max-height:60vh;overflow:auto;box-shadow:0 8px 24px rgba(0,0,0,.2)}
#results a{display:flex;justify-content:space-between;gap:12px;padding:6px 12px;text-decoration:none;color:var(--fg)}
#results a:hover,#results a:focus{background:var(--bg)}
#results small{color:var(--muted)}
nav{display:flex;flex-wrap:wrap;gap:4px 14px;padding:8px 16px;border-bottom:1px solid var(--line)}
nav a{color:var(--muted);text-decoration:none;padding:2px 0;border-bottom:2px solid transparent}
nav a.here,nav a:hover{color:var(--fg);border-bottom-color:var(--accent)}
main{max-width:1200px;margin:0 auto;padding:16px}
h1{margin:.2em 0 .6em;font-size:26px}h2{margin:1.6em 0 .5em;font-size:19px;border-bottom:1px solid var(--line);padding-bottom:4px}
a{color:var(--accent)}
.scroll{overflow-x:auto}
table.data{border-collapse:collapse;width:100%;background:var(--card);font-size:14px}
table.data th,table.data td{padding:5px 9px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
table.data th{cursor:pointer;white-space:nowrap;position:sticky;top:0;background:var(--card);user-select:none}
table.data th.asc::after{content:" \25B2";font-size:10px}table.data th.desc::after{content:" \25BC";font-size:10px}
table.data tbody tr:hover{background:rgba(128,128,128,.1)}
table.data td.num{font-variant-numeric:tabular-nums}
.filter{margin:0 0 8px;padding:6px 10px;width:100%;max-width:320px;border:1px solid var(--line);border-radius:6px;background:var(--card);color:var(--fg)}
dl.facts{display:grid;grid-template-columns:max-content 1fr;gap:3px 18px;background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px 14px}
dl.facts dt{color:var(--muted)}dl.facts dd{margin:0}
.muted{color:var(--muted)}.good{color:var(--good)}.warn{color:var(--warn)}.bad{color:var(--bad)}
.pill{display:inline-block;padding:0 7px;border:1px solid var(--line);border-radius:10px;font-size:12px;color:var(--muted)}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:10px}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px 14px;text-decoration:none;color:var(--fg)}
.card b{display:block;font-size:22px}
img.map{max-width:100%;image-rendering:pixelated;border:1px solid var(--line);border-radius:6px;background:#000}
.note{background:var(--card);border-left:4px solid var(--accent);padding:8px 12px;border-radius:4px;margin:10px 0}
footer{max-width:1200px;margin:24px auto;padding:0 16px;color:var(--muted);font-size:13px}
@media (max-width:640px){main{padding:10px}h1{font-size:22px}}
"""

    val JS = """
(function(){
  function val(td){var s=td.getAttribute('data-sort');return s===null?td.textContent.trim():s;}
  function cmp(a,b){var x=parseFloat(a),y=parseFloat(b);if(!isNaN(x)&&!isNaN(y)&&String(x)===a.trim()&&String(y)===b.trim())return x-y;return a.localeCompare(b,undefined,{numeric:true,sensitivity:'base'});}
  function sortBy(table,idx,th){
    var dir=th.classList.contains('asc')?-1:1;
    table.querySelectorAll('th').forEach(function(h){h.classList.remove('asc','desc');});
    th.classList.add(dir===1?'asc':'desc');
    var body=table.tBodies[0],rows=Array.prototype.slice.call(body.rows);
    rows.sort(function(r1,r2){return dir*cmp(val(r1.cells[idx]),val(r2.cells[idx]));});
    rows.forEach(function(r){body.appendChild(r);});
  }
  document.querySelectorAll('table.data').forEach(function(t){
    t.querySelectorAll('th').forEach(function(th,i){
      th.addEventListener('click',function(){sortBy(t,i,th);});
      th.addEventListener('keydown',function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();sortBy(t,i,th);}});
    });
  });
  document.querySelectorAll('input.filter').forEach(function(inp){
    inp.addEventListener('input',function(){
      var q=inp.value.toLowerCase(),t=document.getElementById(inp.getAttribute('data-table'));
      Array.prototype.forEach.call(t.tBodies[0].rows,function(r){r.hidden=q&&r.textContent.toLowerCase().indexOf(q)<0;});
    });
  });
  var box=document.getElementById('search'),out=document.getElementById('results');
  if(box&&window.WIKI_INDEX){
    box.addEventListener('input',function(){
      var q=box.value.trim().toLowerCase();
      if(!q){out.hidden=true;return;}
      var hits=window.WIKI_INDEX.filter(function(e){return e[1].toLowerCase().indexOf(q)>=0;}).slice(0,25);
      out.innerHTML='';
      hits.forEach(function(e){var a=document.createElement('a');a.href=e[2];
        var n=document.createElement('span');n.textContent=e[1];var k=document.createElement('small');k.textContent=e[0];
        a.appendChild(n);a.appendChild(k);out.appendChild(a);});
      if(!hits.length){var p=document.createElement('p');p.className='muted';p.style.padding='6px 12px';p.textContent='Nothing found.';out.appendChild(p);}
      out.hidden=false;
    });
    document.addEventListener('click',function(e){if(!out.contains(e.target)&&e.target!==box)out.hidden=true;});
  }
})();
"""
}
