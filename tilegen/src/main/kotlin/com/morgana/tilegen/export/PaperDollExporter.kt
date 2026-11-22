package com.morgana.tilegen.export

import com.morgana.tilegen.sprite.Direction8
import com.morgana.tilegen.sprite.PaperDollAction
import com.morgana.tilegen.sprite.PaperDollSlot
import com.morgana.tilegen.sprite.UniversalPaperDollSheet
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Paths produced when exporting a Universal LPC-style paper-doll bundle.
 */
data class PaperDollExportResult(
    val compositePng: Path,
    val layerPngs: Map<PaperDollSlot, Path>,
    val manifestJson: Path,
    val previewHtml: Path?
)

/**
 * Exports a [UniversalPaperDollSheet] to disk as:
 * - `<outDir>/<name>_composite.png`
 * - `<outDir>/layers/<slot>.png` (aligned per-slot sprite sheets for runtime paper-dolling)
 * - `<outDir>/<name>_manifest.json` (grid, animations, direction z-orders, and per-frame sockets)
 * - `<outDir>/<name>_paperdoll_preview.html` (interactive layer-toggling & action previewer)
 */
object PaperDollExporter {

    fun exportUniversal(
        sheet: UniversalPaperDollSheet,
        outDir: Path,
        exportLayers: Boolean = true,
        exportPreview: Boolean = true
    ): PaperDollExportResult {
        Files.createDirectories(outDir)
        val compositePng = outDir.resolve("${sheet.name}_composite.png")
        PngEncoder.writeToFile(sheet.compositeSheet, compositePng)

        val layerPngs = linkedMapOf<PaperDollSlot, Path>()
        if (exportLayers) {
            val layersDir = outDir.resolve("layers")
            Files.createDirectories(layersDir)
            for ((slot, layerImg) in sheet.layerSheets) {
                val p = layersDir.resolve("${slot.layerName}.png")
                PngEncoder.writeToFile(layerImg, p)
                layerPngs[slot] = p
            }
        }

        val manifestJson = outDir.resolve("${sheet.name}_manifest.json")
        Files.writeString(manifestJson, buildManifestJson(sheet))

        val previewHtml = if (exportPreview) {
            val htmlPath = outDir.resolve("${sheet.name}_paperdoll_preview.html")
            Files.writeString(htmlPath, buildPreviewHtml(sheet))
            htmlPath
        } else {
            null
        }

        return PaperDollExportResult(
            compositePng = compositePng,
            layerPngs = layerPngs,
            manifestJson = manifestJson,
            previewHtml = previewHtml
        )
    }

    internal fun buildManifestJson(sheet: UniversalPaperDollSheet): String = buildString {
        append("{\n")
        append("  \"name\": \"${sheet.name}\",\n")
        append("  \"tileSize\": ${sheet.tileSize},\n")
        append("  \"cellWidth\": ${sheet.cellWidth},\n")
        append("  \"cellHeight\": ${sheet.cellHeight},\n")
        append("  \"columns\": ${sheet.columns},\n")
        append("  \"rows\": ${sheet.rows},\n")
        append("  \"width\": ${sheet.compositeSheet.width},\n")
        append("  \"height\": ${sheet.compositeSheet.height},\n")
        append("  \"theme\": {\n")
        append("    \"name\": \"${sheet.theme.name}\",\n")
        append("    \"ancestry\": \"${sheet.theme.ancestry.name.lowercase()}\",\n")
        append("    \"style\": \"${sheet.theme.style.name.lowercase()}\"\n")
        append("  },\n")
        append("  \"layers\": [${sheet.layerSheets.keys.joinToString(", ") { "\"${it.layerName}\"" }}],\n")
        append("  \"actions\": [\n")
        var rowOffset = 0
        sheet.actions.forEachIndexed { aIdx, action ->
            val comma = if (aIdx < sheet.actions.lastIndex) "," else ""
            append("    {\n")
            append("      \"name\": \"${action.name.lowercase()}\",\n")
            append("      \"frames\": ${action.defaultFrames},\n")
            append("      \"frameDurationMs\": ${action.defaultFrameDurationMs},\n")
            append("      \"looping\": ${action.looping},\n")
            append("      \"startRow\": $rowOffset\n")
            append("    }$comma\n")
            rowOffset += sheet.directions.size
        }
        append("  ],\n")
        append("  \"directions\": [${sheet.directions.joinToString(", ") { "\"${it.name.lowercase()}\"" }}],\n")
        append("  \"zOrderByDirection\": {\n")
        sheet.directions.forEachIndexed { dIdx, dir ->
            val comma = if (dIdx < sheet.directions.lastIndex) "," else ""
            val zList = PaperDollSlot.zOrderFor(dir, PaperDollAction.WALK)
                .joinToString(", ") { "\"${it.layerName}\"" }
            append("    \"${dir.name.lowercase()}\": [$zList]$comma\n")
        }
        append("  },\n")
        append("  \"frames\": [\n")
        sheet.frameMetadata.forEachIndexed { idx, fm ->
            val comma = if (idx < sheet.frameMetadata.lastIndex) "," else ""
            val s = fm.sockets
            append("    {\"action\": \"${fm.action.name.lowercase()}\", \"direction\": \"${fm.direction.name.lowercase()}\", \"frame\": ${fm.frameIndex}, \"col\": ${fm.col}, \"row\": ${fm.row}, \"durationMs\": ${fm.durationMs}, \"sockets\": {\"pivot\": [${s.pivotX}, ${s.pivotY}], \"head\": [${s.headX}, ${s.headY}], \"face\": [${s.faceX}, ${s.faceY}], \"back\": [${s.backX}, ${s.backY}], \"shoulder\": [${s.shoulderX}, ${s.shoulderY}], \"waist\": [${s.waistX}, ${s.waistY}], \"mainHand\": [${s.mainHandX}, ${s.mainHandY}], \"offHand\": [${s.offHandX}, ${s.offHandY}]}}$comma\n")
        }
        append("  ]\n")
        append("}")
    }

    private fun buildPreviewHtml(sheet: UniversalPaperDollSheet): String {
        val layerDataUris = sheet.layerSheets.entries.joinToString(",\n") { (slot, img) ->
            val b64 = Base64.getEncoder().encodeToString(PngEncoder.encode(img))
            "      \"${slot.layerName}\": \"data:image/png;base64,$b64\""
        }
        val manifestJson = buildManifestJson(sheet)
        val slotCheckboxes = sheet.layerSheets.keys.joinToString("\n") { slot ->
            """<label class="layer-toggle"><input type="checkbox" data-slot="${slot.layerName}" checked> ${slot.layerName}</label>"""
        }
        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>${sheet.name} — Universal Paper-Doll Preview</title>
  <style>
    :root { color-scheme: dark; --bg: #13161f; --panel: #1d2230; --accent: #68b5ff; --text: #e6ebf5; }
    body { margin: 0; font-family: system-ui, sans-serif; background: var(--bg); color: var(--text); display: grid; grid-template-columns: 320px 1fr; min-height: 100vh; }
    aside { background: var(--panel); padding: 16px; border-right: 1px solid #2d3548; overflow-y: auto; }
    main { padding: 24px; display: flex; flex-direction: column; align-items: center; gap: 16px; }
    h1 { font-size: 1.1rem; margin: 0 0 12px; color: var(--accent); }
    h2 { font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.06em; color: #9aa7c0; margin: 16px 0 8px; }
    select, button { background: #262d3f; color: var(--text); border: 1px solid #39435c; border-radius: 6px; padding: 6px 10px; font-size: 0.9rem; }
    .controls { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .layer-list { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
    .layer-toggle { font-size: 0.82rem; display: flex; align-items: center; gap: 6px; background: #23293a; padding: 5px 8px; border-radius: 5px; cursor: pointer; }
    canvas { image-rendering: pixelated; background: repeating-conic-gradient(#1f2433 0% 25%, #272d3f 0% 50%) 50% / 16px 16px; border: 1px solid #3c4763; border-radius: 8px; }
    .meta { font-size: 0.85rem; color: #9aa7c0; }
  </style>
</head>
<body>
  <aside>
    <h1>${sheet.name}</h1>
    <div class="meta">Theme: ${sheet.theme.name} (${sheet.theme.ancestry.name.lowercase()}) · ${sheet.cellWidth}×${sheet.cellHeight}</div>
    <h2>Action & Direction</h2>
    <div class="controls">
      <select id="actionSel"></select>
      <select id="dirSel"></select>
      <button id="playBtn">Pause</button>
      <button id="zoomBtn">Zoom: 6x</button>
      <label class="layer-toggle"><input type="checkbox" id="socketChk"> Sockets</label>
    </div>
    <h2>Paper-Doll Layers</h2>
    <div class="layer-list" id="layerList">
      $slotCheckboxes
    </div>
  </aside>
  <main>
    <canvas id="stage"></canvas>
    <div class="meta" id="frameInfo"></div>
  </main>
  <script>
    const MANIFEST = $manifestJson;
    const LAYER_URIS = {
$layerDataUris
    };
    const images = {};
    let loaded = 0;
    const total = Object.keys(LAYER_URIS).length;
    const stage = document.getElementById('stage');
    const ctx = stage.getContext('2d');
    const actionSel = document.getElementById('actionSel');
    const dirSel = document.getElementById('dirSel');
    const playBtn = document.getElementById('playBtn');
    const zoomBtn = document.getElementById('zoomBtn');
    const socketChk = document.getElementById('socketChk');
    const frameInfo = document.getElementById('frameInfo');

    let zoom = 6;
    let playing = true;
    let frameIdx = 0;
    let timer = null;

    MANIFEST.actions.forEach(a => {
      const opt = document.createElement('option');
      opt.value = a.name; opt.textContent = a.name;
      actionSel.appendChild(opt);
    });
    MANIFEST.directions.forEach(d => {
      const opt = document.createElement('option');
      opt.value = d; opt.textContent = d;
      dirSel.appendChild(opt);
    });

    function activeSlots() {
      const set = new Set();
      document.querySelectorAll('#layerList input[data-slot]').forEach(el => {
        if (el.checked) set.add(el.dataset.slot);
      });
      return set;
    }

    function render() {
      if (loaded < total) return;
      const act = MANIFEST.actions.find(a => a.name === actionSel.value) || MANIFEST.actions[0];
      const dir = dirSel.value || MANIFEST.directions[0];
      const f = frameIdx % act.frames;
      const frameMeta = MANIFEST.frames.find(m => m.action === act.name && m.direction === dir && m.frame === f);
      if (!frameMeta) return;

      const cw = MANIFEST.cellWidth || MANIFEST.tileSize;
      const ch = MANIFEST.cellHeight || MANIFEST.tileSize;
      stage.width = cw * zoom;
      stage.height = ch * zoom;
      ctx.imageSmoothingEnabled = false;
      ctx.clearRect(0, 0, stage.width, stage.height);

      const enabled = activeSlots();
      const zOrder = MANIFEST.zOrderByDirection[dir] || Object.keys(LAYER_URIS);
      for (const slot of zOrder) {
        if (!enabled.has(slot) || !images[slot]) continue;
        ctx.drawImage(
          images[slot],
          frameMeta.col * cw, frameMeta.row * ch, cw, ch,
          0, 0, stage.width, stage.height
        );
      }

      if (socketChk.checked) {
        const colors = { mainHand: '#ff6666', offHand: '#66aaff', head: '#ffee66', back: '#66ff99', pivot: '#ffffff' };
        for (const [k, pt] of Object.entries(frameMeta.sockets)) {
          ctx.fillStyle = colors[k] || '#ff99ff';
          ctx.fillRect(pt[0] * zoom - 2, pt[1] * zoom - 2, 5, 5);
        }
      }
      frameInfo.textContent = `Action: ${'$'}{act.name} | Dir: ${'$'}{dir} | Frame: ${'$'}{f + 1}/${'$'}{act.frames} (${'$'}{act.frameDurationMs}ms)`;
    }

    function schedule() {
      if (timer) clearInterval(timer);
      const act = MANIFEST.actions.find(a => a.name === actionSel.value) || MANIFEST.actions[0];
      timer = setInterval(() => {
        if (playing) { frameIdx = (frameIdx + 1) % act.frames; render(); }
      }, act.frameDurationMs);
    }

    Object.entries(LAYER_URIS).forEach(([slot, uri]) => {
      const img = new Image();
      img.onload = () => { loaded++; if (loaded === total) { render(); schedule(); } };
      img.src = uri;
      images[slot] = img;
    });

    actionSel.addEventListener('change', () => { frameIdx = 0; render(); schedule(); });
    dirSel.addEventListener('change', () => { render(); });
    socketChk.addEventListener('change', () => { render(); });
    document.getElementById('layerList').addEventListener('change', () => { render(); });
    playBtn.addEventListener('click', () => { playing = !playing; playBtn.textContent = playing ? 'Pause' : 'Play'; });
    zoomBtn.addEventListener('click', () => { zoom = zoom === 6 ? 8 : (zoom === 8 ? 4 : 6); zoomBtn.textContent = `Zoom: ${'$'}{zoom}x`; render(); });
  </script>
</body>
</html>"""
    }
}
