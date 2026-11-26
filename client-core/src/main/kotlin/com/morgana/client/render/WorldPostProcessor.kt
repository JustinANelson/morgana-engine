package com.morgana.client.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws the world into an offscreen buffer, builds a low-resolution light map (ambient sky colour plus point lights), then
 * composites both to the screen through one shader: light map multiply, split-toned palette, tilt-shift depth of field,
 * drifting mist and a vignette. The sprites and tiles are untouched, so every existing asset and editor tool keeps working.
 *
 * If the platform cannot make framebuffers or compile the shader, [VisualFx.available] is cleared and callers draw the plain world.
 */
class WorldPostProcessor {
    private var scene: FrameBuffer? = null
    private var lightMap: FrameBuffer? = null
    private var shader: ShaderProgram? = null
    private var lightTexture: Texture? = null
    private var width = 0
    private var height = 0
    private var time = 0f
    private val screenProjection = Matrix4()
    private val sceneRegion = TextureRegion()
    private val tmpColor = Color()

    /** Draws [drawWorld] (which must do its own batch begin/end) through the effects. Falls back to a plain draw on any failure. */
    fun render(
        batch: SpriteBatch,
        camera: OrthographicCamera,
        map: MapDefinition,
        sky: SkyState?,
        deltaSeconds: Float,
        drawWorld: () -> Unit
    ) {
        if (!ensureResources()) {
            drawWorld()
            return
        }
        time += deltaSeconds
        val sceneBuffer = scene!!
        val lightBuffer = lightMap!!

        sceneBuffer.begin()
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.12f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        drawWorld()
        sceneBuffer.end()

        drawLightMap(batch, camera, map, sky, lightBuffer)
        composite(batch, sceneBuffer, lightBuffer, sky)
    }

    private fun drawLightMap(batch: SpriteBatch, camera: OrthographicCamera, map: MapDefinition, sky: SkyState?, target: FrameBuffer) {
        val outdoors = sky != null && !map.isIndoors
        target.begin()
        // Outdoors the floor of the light map is the sky colour; elsewhere it is white, so the existing indoor darkness is not doubled.
        if (outdoors) Gdx.gl.glClearColor(sky!!.ambientR, sky.ambientG, sky.ambientB, 1f) else Gdx.gl.glClearColor(1f, 1f, 1f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        val night = if (outdoors) 1f - sky!!.daylight else 0f
        if (night > 0.02f) {
            batch.projectionMatrix = camera.combined
            batch.begin()
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
            val ts = map.tileSize
            val halfW = camera.viewportWidth * camera.zoom * 0.5f + 256f
            val halfH = camera.viewportHeight * camera.zoom * 0.5f + 256f
            val x0 = floor((camera.position.x - halfW) / ts).toInt().coerceAtLeast(0)
            val x1 = ceil((camera.position.x + halfW) / ts).toInt().coerceAtMost(map.width - 1)
            val worldBottom = camera.position.y - halfH
            val worldTop = camera.position.y + halfH
            val tex = lightTexture!!
            for (ty in 0 until map.height) {
                val ry = ((map.height - 1 - ty) * ts).toFloat()
                if (ry < worldBottom || ry > worldTop) continue
                for (tx in x0..x1) {
                    val attr = map.getCell(tx, ty)?.attribute
                    if (attr is TileAttribute.LightSource) {
                        val c = runCatching { Color.valueOf(attr.colorHex.removePrefix("#")) }.getOrDefault(Color.ORANGE)
                        val flicker = if (attr.flicker) 1f + 0.05f * kotlin.math.sin(time * 7f + tx * 1.7f + ty) else 1f
                        val r = attr.radiusPixels * 1.6f * flicker
                        val cx = tx * ts + ts * 0.5f
                        val cy = ry + ts * 0.5f
                        batch.setColor(c.r, c.g, c.b, 0.95f)
                        batch.draw(tex, cx - r, cy - r, r * 2f, r * 2f)
                    }
                }
            }
            // A small warm lantern around the player so night stays playable away from fires.
            if (outdoors) {
                val r = 150f
                batch.setColor(1f, 0.82f, 0.55f, 0.55f * night)
                batch.draw(tex, camera.position.x - r, camera.position.y - r, r * 2f, r * 2f)
            }
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            batch.color = Color.WHITE
            batch.end()
        }
        target.end()
    }

    private fun composite(batch: SpriteBatch, sceneBuffer: FrameBuffer, lightBuffer: FrameBuffer, sky: SkyState?) {
        val sh = shader!!
        screenProjection.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
        batch.projectionMatrix = screenProjection
        batch.shader = sh
        batch.disableBlending()
        batch.begin()
        sh.setUniformi("u_light", 1)
        sh.setUniformf("u_texel", 1f / width, 1f / height)
        sh.setUniformf("u_time", time)
        sh.setUniformf("u_aspect", width.toFloat() / height)
        sh.setUniformf("u_lighting", if (VisualFx.lighting) 1f else 0f)
        sh.setUniformf("u_tone", if (VisualFx.lighting) 1f else 0f)
        sh.setUniformf("u_tilt", if (VisualFx.hd2d && VisualFx.tiltShift) 1f else 0f)
        sh.setUniformf("u_hd", if (VisualFx.hd2d) 1f else 0f)
        // Bloom is stronger at night, when lit windows and fires are the brightest things on screen.
        sh.setUniformf("u_bloom", if (VisualFx.hd2d) 0.9f + 0.9f * (1f - (sky?.daylight ?: 1f)) else 0f)
        val warm = sky?.warmth ?: 0f
        val ambientLum = if (sky != null) (sky.ambientR + sky.ambientG + sky.ambientB) / 3f else 1f
        sh.setUniformf("u_mist", if (VisualFx.lighting && sky != null) 0.05f + 0.14f * warm else 0f)
        tmpColor.set(0.78f * ambientLum + 0.08f, 0.84f * ambientLum + 0.08f, 0.92f * ambientLum + 0.08f, 1f)
        sh.setUniformf("u_mistColor", tmpColor.r, tmpColor.g, tmpColor.b)

        lightBuffer.colorBufferTexture.bind(1)
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        sceneRegion.setRegion(sceneBuffer.colorBufferTexture)
        sceneRegion.flip(false, true)
        batch.draw(sceneRegion, 0f, 0f, width.toFloat(), height.toFloat())
        batch.end()
        batch.enableBlending()
        batch.shader = null
    }

    private fun ensureResources(): Boolean {
        if (!VisualFx.available) return false
        val w = Gdx.graphics.width
        val h = Gdx.graphics.height
        if (w <= 0 || h <= 0) return false
        try {
            if (shader == null) {
                ShaderProgram.pedantic = false
                val program = ShaderProgram(VERTEX, FRAGMENT)
                if (!program.isCompiled) {
                    Gdx.app.error("WorldPostProcessor", "Shader failed to compile: ${program.log}")
                    VisualFx.available = false
                    return false
                }
                shader = program
                lightTexture = makeLightTexture()
            }
            if (scene == null || w != width || h != height) {
                scene?.dispose()
                lightMap?.dispose()
                scene = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false).also {
                    it.colorBufferTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                }
                lightMap = FrameBuffer(Pixmap.Format.RGBA8888, (w / 2).coerceAtLeast(1), (h / 2).coerceAtLeast(1), false).also {
                    it.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                }
                width = w
                height = h
            }
        } catch (t: Throwable) {
            Gdx.app.error("WorldPostProcessor", "Post-processing unavailable on this device: ${t.message}")
            VisualFx.available = false
            return false
        }
        return true
    }

    /** A soft white radial falloff, used for every light in the light map. */
    private fun makeLightTexture(): Texture {
        val size = 128
        val pm = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val center = (size - 1) / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = (x - center) / center
            val dy = (y - center) / center
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            val a = (1f - dist).coerceIn(0f, 1f)
            pm.setColor(1f, 1f, 1f, a * a)
            pm.drawPixel(x, y)
        }
        return Texture(pm).also {
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            pm.dispose()
        }
    }

    fun dispose() {
        scene?.dispose(); scene = null
        lightMap?.dispose(); lightMap = null
        shader?.dispose(); shader = null
        lightTexture?.dispose(); lightTexture = null
    }

    private companion object {
        const val VERTEX = """
attribute vec4 a_position;
attribute vec4 a_color;
attribute vec2 a_texCoord0;
uniform mat4 u_projTrans;
varying vec4 v_color;
varying vec2 v_texCoords;
void main() {
    v_color = a_color;
    v_texCoords = a_texCoord0;
    gl_Position = u_projTrans * a_position;
}
"""

        const val FRAGMENT = """
#ifdef GL_ES
precision mediump float;
#endif
varying vec4 v_color;
varying vec2 v_texCoords;
uniform sampler2D u_texture;
uniform sampler2D u_light;
uniform vec2 u_texel;
uniform float u_time;
uniform float u_aspect;
uniform float u_lighting;
uniform float u_tone;
uniform float u_tilt;
uniform float u_hd;
uniform float u_bloom;
uniform float u_mist;
uniform vec3 u_mistColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    vec2 uv = v_texCoords;
    // 0 along the horizontal centre band, 1 at the top and bottom edges.
    float edge = abs(uv.y - 0.5) * 2.0;

    vec3 c = texture2D(u_texture, uv).rgb;
    float blur = u_tilt * smoothstep(0.42, 1.0, edge);
    if (blur > 0.02) {
        vec2 o = u_texel * (1.0 + blur * 2.5);
        vec3 s = c * 0.25;
        s += texture2D(u_texture, uv + vec2(o.x, 0.0)).rgb * 0.125;
        s += texture2D(u_texture, uv - vec2(o.x, 0.0)).rgb * 0.125;
        s += texture2D(u_texture, uv + vec2(0.0, o.y)).rgb * 0.125;
        s += texture2D(u_texture, uv - vec2(0.0, o.y)).rgb * 0.125;
        s += texture2D(u_texture, uv + o).rgb * 0.0625;
        s += texture2D(u_texture, uv - o).rgb * 0.0625;
        s += texture2D(u_texture, uv + vec2(o.x, -o.y)).rgb * 0.0625;
        s += texture2D(u_texture, uv + vec2(-o.x, o.y)).rgb * 0.0625;
        c = mix(c, s, clamp(blur, 0.0, 1.0));
    }

    // Light map: sky ambient with point lights added on top.
    vec3 light = texture2D(u_light, uv).rgb;
    c *= mix(vec3(1.0), light, u_lighting);

    // Bloom: bright pixels (lit windows, fires, magic, sunlit highlights) bleed a soft halo. Two rings of taps from the scene, lit by the light map.
    if (u_bloom > 0.001) {
        vec3 glow = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.785398;
            vec2 d = vec2(cos(a), sin(a));
            vec3 s1 = texture2D(u_texture, uv + d * u_texel * 5.0).rgb;
            vec3 s2 = texture2D(u_texture, uv + d * u_texel * 11.0).rgb;
            vec3 l1 = texture2D(u_light, uv + d * u_texel * 5.0).rgb;
            vec3 l2 = texture2D(u_light, uv + d * u_texel * 11.0).rgb;
            s1 *= mix(vec3(1.0), l1, u_lighting);
            s2 *= mix(vec3(1.0), l2, u_lighting);
            glow += max(s1 - 0.68, 0.0) * 0.6 + max(s2 - 0.68, 0.0) * 0.4;
        }
        c += glow * (u_bloom / 8.0);
    }

    // Split-toned palette: shadows lean cool blue-violet, highlights lean warm yellow.
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    vec3 tone = mix(vec3(0.90, 0.94, 1.08), vec3(1.07, 1.03, 0.91), smoothstep(0.12, 0.85, lum));
    c = mix(c, c * tone, 0.6 * u_tone);

    // Slightly punchier colour for the HD-2D look.
    c = mix(vec3(lum), c, 1.0 + 0.16 * u_hd);

    // Drifting mist, thickest toward the top (the far side of a tilted view).
    if (u_mist > 0.001) {
        vec2 p = vec2(uv.x * u_aspect, uv.y) * 3.2;
        float n = vnoise(p + vec2(u_time * 0.03, u_time * 0.012)) * 0.6 + vnoise(p * 2.3 - vec2(u_time * 0.02, 0.0)) * 0.4;
        float fog = u_mist * smoothstep(0.25, 0.85, n) * (0.45 + 0.55 * smoothstep(0.2, 1.0, uv.y));
        c = mix(c, u_mistColor, clamp(fog, 0.0, 0.6));
    }

    // Vignette.
    float v = distance(uv, vec2(0.5)) * 1.25;
    c *= 1.0 - u_hd * 0.30 * smoothstep(0.45, 1.0, v);

    gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
