package com.morgana.tilegen.export

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.AutotileSheet

/**
 * Generates a self-contained `preview.html` contact-sheet inspector and interactive
 * autotile / animation previewer using pure HTML5 Canvas (`image-rendering: pixelated`).
 */
object PreviewHtmlExporter {

    fun toNamedPreviewHtml(
        name: String,
        imageFileName: String,
        tileSize: Int,
        columns: Int,
        tiles: List<NamedTile>
    ): String {
        val tilesJson = tiles.mapIndexed { idx, t ->
            val col = idx % columns
            val row = idx / columns
            """{"index":$idx,"name":"${escapeJs(t.name)}","col":$col,"row":$row}"""
        }.joinToString(",")
        val animGroups = TiledExporter.detectAnimationGroups(tiles)
        val animsJson = animGroups.entries.joinToString(",") { (animName, indices) ->
            val framesJson = indices.joinToString(",") { idx ->
                val c = idx % columns
                val r = idx / columns
                "[$c,$r]"
            }
            """{"name":"${escapeJs(animName)}","frames":[$framesJson]}"""
        }
        return buildHtml(
            title = "$name — TilesetGen Preview",
            imageFileName = imageFileName,
            tileSize = tileSize,
            columns = columns,
            tilesJson = "[$tilesJson]",
            autotileMode = null,
            autotileLookupJson = "{}",
            animationsJson = "[$animsJson]",
            hasAnimations = animGroups.isNotEmpty()
        )
    }

    fun toAutotilePreviewHtml(
        name: String,
        imageFileName: String,
        autotile: AutotileSheet
    ): String {
        val tilesJson = autotile.entries.joinToString(",") { e ->
            """{"index":${e.index},"name":"${escapeJs(e.name)}","col":${e.col},"row":${e.row},"mask":${e.mask}}"""
        }
        val lookupJson = autotile.entries.joinToString(",") { e ->
            """"${e.mask}":[${e.col},${e.row}]"""
        }
        return buildHtml(
            title = "$name — Autotile Preview",
            imageFileName = imageFileName,
            tileSize = autotile.tileSize,
            columns = autotile.columns,
            tilesJson = "[$tilesJson]",
            autotileMode = autotile.mode.name,
            autotileLookupJson = "{$lookupJson}",
            animationsJson = "[]",
            hasAnimations = false
        )
    }

    private fun buildHtml(
        title: String,
        imageFileName: String,
        tileSize: Int,
        columns: Int,
        tilesJson: String,
        autotileMode: String?,
        autotileLookupJson: String,
        animationsJson: String = "[]",
        hasAnimations: Boolean = false
    ): String {
        val modeJs = if (autotileMode == null) "null" else "\"$autotileMode\""
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<title>${escapeHtml(title)}</title>
<style>
  body { font-family: system-ui, sans-serif; background: #181822; color: #e6e6f0; margin: 0; padding: 20px; }
  h1 { font-size: 18px; margin: 0 0 12px; }
  .toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
  button, select { background: #262636; color: #e6e6f0; border: 1px solid #3f3f58; padding: 5px 10px; border-radius: 4px; cursor: pointer; }
  .layout { display: flex; gap: 24px; flex-wrap: wrap; align-items: flex-start; }
  .panel { background: #20202e; border: 1px solid #323248; border-radius: 6px; padding: 12px; }
  canvas { image-rendering: pixelated; border: 1px solid #3f3f58; background: repeating-conic-gradient(#242433 0% 25%, #2b2b3d 0% 50%) 50% / 16px 16px; display: block; }
  #info { font-family: monospace; font-size: 13px; color: #9ecbff; min-height: 20px; margin-top: 8px; }
</style>
</head>
<body>
<h1>${escapeHtml(title)}</h1>
<div class="toolbar">
  <label>Zoom: <select id="zoom"><option value="1">1x</option><option value="2">2x</option><option value="4" selected>4x</option><option value="8">8x</option></select></label>
  <label><input type="checkbox" id="grid" checked/> Grid</label>
  <span id="info">Hover a tile to inspect</span>
</div>
<div class="layout">
  <div class="panel">
    <div style="margin-bottom:6px;font-size:13px;color:#b0b0c8;">Contact Sheet (${tileSize}x${tileSize}px)</div>
    <canvas id="sheet"></canvas>
  </div>
  ${if (autotileMode != null) """
  <div class="panel">
    <div style="margin-bottom:6px;font-size:13px;color:#b0b0c8;">Interactive Autotile Painter (Click/Drag to paint, Right-Click to erase)</div>
    <canvas id="map"></canvas>
  </div>""" else ""}
  ${if (hasAnimations) """
  <div class="panel">
    <div style="margin-bottom:6px;font-size:13px;color:#b0b0c8;">Animation Preview</div>
    <div style="display:flex;gap:8px;margin-bottom:8px;align-items:center;">
      <select id="animSelect"></select>
      <select id="animFps"><option value="4">4 FPS</option><option value="6" selected>6 FPS</option><option value="10">10 FPS</option></select>
    </div>
    <canvas id="animCanvas"></canvas>
  </div>""" else ""}
</div>
<script>
const TILE_SIZE = $tileSize;
const TILES = $tilesJson;
const AUTOTILE_MODE = $modeJs;
const MASK_LOOKUP = $autotileLookupJson;
const ANIMATIONS = $animationsJson;
const img = new Image();
img.src = "${escapeJs(imageFileName)}";
const sheetCanvas = document.getElementById("sheet");
const ctx = sheetCanvas.getContext("2d");
const zoomEl = document.getElementById("zoom");
const gridEl = document.getElementById("grid");
const infoEl = document.getElementById("info");

function drawSheet() {
  if (!img.complete) return;
  const z = parseInt(zoomEl.value, 10);
  sheetCanvas.width = img.width * z;
  sheetCanvas.height = img.height * z;
  ctx.imageSmoothingEnabled = false;
  ctx.clearRect(0, 0, sheetCanvas.width, sheetCanvas.height);
  ctx.drawImage(img, 0, 0, sheetCanvas.width, sheetCanvas.height);
  if (gridEl.checked) {
    ctx.strokeStyle = "rgba(255,255,255,0.2)";
    ctx.lineWidth = 1;
    for (let x = 0; x <= sheetCanvas.width; x += TILE_SIZE * z) {
      ctx.beginPath(); ctx.moveTo(x + 0.5, 0); ctx.lineTo(x + 0.5, sheetCanvas.height); ctx.stroke();
    }
    for (let y = 0; y <= sheetCanvas.height; y += TILE_SIZE * z) {
      ctx.beginPath(); ctx.moveTo(0, y + 0.5); ctx.lineTo(sheetCanvas.width, y + 0.5); ctx.stroke();
    }
  }
}

sheetCanvas.addEventListener("mousemove", (e) => {
  const z = parseInt(zoomEl.value, 10);
  const rect = sheetCanvas.getBoundingClientRect();
  const col = Math.floor((e.clientX - rect.left) / (TILE_SIZE * z));
  const row = Math.floor((e.clientY - rect.top) / (TILE_SIZE * z));
  const tile = TILES.find(t => t.col === col && t.row === row);
  infoEl.textContent = tile
    ? `#${'$'}{tile.index} "${'$'}{tile.name}" (col=${'$'}{col}, row=${'$'}{row}, px=${'$'}{col * TILE_SIZE},${'$'}{row * TILE_SIZE})`
    : `col=${'$'}{col}, row=${'$'}{row}`;
});

zoomEl.addEventListener("change", () => { drawSheet(); if (AUTOTILE_MODE) drawMap(); });
gridEl.addEventListener("change", drawSheet);

const mapCanvas = document.getElementById("map");
const COLS = 12, ROWS = 10;
const grid = Array.from({length: ROWS}, (_, r) => Array.from({length: COLS}, (_, c) => (r >= 2 && r <= 7 && c >= 2 && c <= 9) ? 1 : 0));

function canonicalMask(r, c) {
  const on = (rr, cc) => (rr >= 0 && rr < ROWS && cc >= 0 && cc < COLS) ? grid[rr][cc] : 0;
  const n = on(r-1, c), e = on(r, c+1), s = on(r+1, c), w = on(r, c-1);
  if (AUTOTILE_MODE === "CARDINAL_16") return (n?1:0)|(e?2:0)|(s?4:0)|(w?8:0);
  let m = (n?1:0)|(e?4:0)|(s?16:0)|(w?64:0);
  if (n && e && on(r-1, c+1)) m |= 2;
  if (e && s && on(r+1, c+1)) m |= 8;
  if (s && w && on(r+1, c-1)) m |= 32;
  if (w && n && on(r-1, c-1)) m |= 128;
  return m;
}

function drawMap() {
  if (!mapCanvas || !img.complete) return;
  const z = Math.min(4, parseInt(zoomEl.value, 10));
  mapCanvas.width = COLS * TILE_SIZE * z;
  mapCanvas.height = ROWS * TILE_SIZE * z;
  const mctx = mapCanvas.getContext("2d");
  mctx.imageSmoothingEnabled = false;
  mctx.clearRect(0, 0, mapCanvas.width, mapCanvas.height);
  for (let r = 0; r < ROWS; r++) {
    for (let c = 0; c < COLS; c++) {
      const mask = grid[r][c] ? canonicalMask(r, c) : 0;
      const pos = grid[r][c] ? (MASK_LOOKUP[String(mask)] || [0,0]) : MASK_LOOKUP["0"];
      if (pos) {
        mctx.drawImage(img, pos[0]*TILE_SIZE, pos[1]*TILE_SIZE, TILE_SIZE, TILE_SIZE, c*TILE_SIZE*z, r*TILE_SIZE*z, TILE_SIZE*z, TILE_SIZE*z);
      }
    }
  }
}

if (mapCanvas) {
  let painting = 0;
  const paintAt = (e) => {
    const z = Math.min(4, parseInt(zoomEl.value, 10));
    const rect = mapCanvas.getBoundingClientRect();
    const c = Math.floor((e.clientX - rect.left) / (TILE_SIZE * z));
    const r = Math.floor((e.clientY - rect.top) / (TILE_SIZE * z));
    if (r >= 0 && r < ROWS && c >= 0 && c < COLS) {
      grid[r][c] = painting;
      drawMap();
    }
  };
  mapCanvas.addEventListener("contextmenu", e => e.preventDefault());
  mapCanvas.addEventListener("mousedown", e => { painting = (e.button === 2) ? 0 : 1; paintAt(e); });
  window.addEventListener("mouseup", () => { painting = -1; });
  mapCanvas.addEventListener("mousemove", e => { if (painting >= 0) paintAt(e); });
}

img.onload = () => {
  drawSheet();
  if (AUTOTILE_MODE) drawMap();
  initAnimationPreview();
};

function initAnimationPreview() {
  const animCanvas = document.getElementById("animCanvas");
  const animSelect = document.getElementById("animSelect");
  const animFps = document.getElementById("animFps");
  if (!animCanvas || !animSelect || !ANIMATIONS.length) return;
  ANIMATIONS.forEach((a, i) => {
    const opt = document.createElement("option");
    opt.value = String(i);
    opt.textContent = `${'$'}{a.name} (${'$'}{a.frames.length}f)`;
    animSelect.appendChild(opt);
  });
  let tick = 0;
  function step() {
    const anim = ANIMATIONS[parseInt(animSelect.value, 10) || 0];
    if (anim && anim.frames.length) {
      const z = Math.max(4, parseInt(zoomEl.value, 10));
      animCanvas.width = TILE_SIZE * z;
      animCanvas.height = TILE_SIZE * z;
      const actx = animCanvas.getContext("2d");
      actx.imageSmoothingEnabled = false;
      actx.clearRect(0, 0, animCanvas.width, animCanvas.height);
      const [col, row] = anim.frames[tick % anim.frames.length];
      actx.drawImage(img, col * TILE_SIZE, row * TILE_SIZE, TILE_SIZE, TILE_SIZE, 0, 0, TILE_SIZE * z, TILE_SIZE * z);
      tick++;
    }
    const fps = parseInt(animFps.value, 10) || 6;
    setTimeout(step, Math.round(1000 / fps));
  }
  step();
}
</script>
</body>
</html>"""
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeJs(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
