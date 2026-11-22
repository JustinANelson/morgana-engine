package com.morgana.client.editor

import com.morgana.domain.game.SpriteProfile
import com.morgana.protocol.realms.AssetKind

/**
 * Pure, LibGDX- and AWT-free client validation for in-editor PNG art uploads (safe for JVM, Android, and TeaVM).
 * Mirrors the server rules in `GameAssets` so malformed PNGs or wrong sheet dimensions fail fast before upload.
 */
object ArtImportValidator {
    const val TILE_SIZE = 32
    const val MIN_SINGLE = 8
    const val MAX_SINGLE = 256
    const val MAX_DIMENSION = 4096
    const val MAX_ANIMATION_FRAMES = 64
    const val MAX_UPLOAD_BYTES = 1024 * 1024

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PICTURE_NAME_PATTERN = Regex("[a-zA-Z0-9_-]{1,48}")

    /** Width and height from a PNG IHDR chunk, or null if [bytes] is not a valid PNG header. */
    fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return null
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() || bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()) {
            return null
        }
        fun intAt(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        return intAt(16) to intAt(20)
    }

    /** Human-readable error if [assetId] is invalid for [kind], or null when valid. */
    fun validateAssetId(kind: AssetKind, assetId: String): String? {
        val trimmed = assetId.trim().removeSuffix(".png")
        if (kind == AssetKind.MISC) {
            return if (PICTURE_NAME_PATTERN.matches(trimmed)) null else "Picture name must be 1..48 letters, digits, '-' or '_'"
        }
        val parsed = trimmed.toIntOrNull()
        return if (parsed != null && parsed > 0 && parsed.toString() == trimmed) null else "Asset id must be a positive whole number"
    }

    /** Human-readable error if [bytes] cannot be imported for [kind] and [assetId], or null when ready to upload. */
    fun validatePngUpload(
        kind: AssetKind,
        assetId: String,
        bytes: ByteArray,
        sprites: SpriteProfile? = null
    ): String? {
        validateAssetId(kind, assetId)?.let { return it }
        if (bytes.isEmpty()) return "PNG file is empty"
        if (bytes.size > MAX_UPLOAD_BYTES) {
            return "PNG size (${bytes.size / 1024} KB) exceeds ${MAX_UPLOAD_BYTES / 1024} KB limit"
        }
        val (w, h) = pngSize(bytes) ?: return "Selected file is not a valid PNG"
        if (w <= 0 || h <= 0) return "PNG has no pixels (${w}x$h)"
        if (w > MAX_DIMENSION || h > MAX_DIMENSION) return "PNG (${w}x$h) is larger than ${MAX_DIMENSION}x$MAX_DIMENSION"
        return when (kind) {
            AssetKind.TILESET ->
                if (w % TILE_SIZE != 0 || h % TILE_SIZE != 0) "Tileset sides must be multiples of $TILE_SIZE (got ${w}x$h)" else null
            AssetKind.SPRITE -> when {
                sprites != null && (w != sprites.sheetWidth || h != sprites.sheetHeight) ->
                    "Sprite sheet must be ${sprites.sheetWidth}x${sprites.sheetHeight} (${sprites.cellWidth}x${sprites.cellHeight}, ${sprites.totalFrames}x4, got ${w}x$h)"
                sprites == null && (w % 3 != 0 || h % 4 != 0) ->
                    "Sprite sheet must be 3 frame columns x 4 direction rows (width % 3 == 0, height % 4 == 0, got ${w}x$h)"
                else -> null
            }
            AssetKind.ICON, AssetKind.PROJECTILE ->
                if (w != h || w < MIN_SINGLE || w > MAX_SINGLE) "Image must be square from $MIN_SINGLE to $MAX_SINGLE px (got ${w}x$h)" else null
            AssetKind.PAPERDOLL -> {
                val isLegacySquare = w == h && w in MIN_SINGLE..MAX_SINGLE
                val isDirectionalSheet = sprites != null && w == sprites.sheetWidth && h == sprites.sheetHeight
                when {
                    isLegacySquare || isDirectionalSheet -> null
                    sprites != null ->
                        "Paperdoll must be square ($MIN_SINGLE..$MAX_SINGLE px) or a ${sprites.sheetWidth}x${sprites.sheetHeight} directional sheet (got ${w}x$h)"
                    else -> "Image must be square from $MIN_SINGLE to $MAX_SINGLE px (got ${w}x$h)"
                }
            }
            AssetKind.ANIMATION -> {
                val frames = if (h > 0 && w % h == 0) w / h else 0
                if (h < MIN_SINGLE || h > MAX_SINGLE || frames !in 1..MAX_ANIMATION_FRAMES) {
                    "Animation strip must be 1..$MAX_ANIMATION_FRAMES square frames wide ($MIN_SINGLE..$MAX_SINGLE px tall, got ${w}x$h)"
                } else null
            }
            AssetKind.MISC -> null
            AssetKind.SOUND, AssetKind.MUSIC, AssetKind.FONT -> "${kind.name.lowercase()} files cannot be imported as PNGs"
        }
    }
}
