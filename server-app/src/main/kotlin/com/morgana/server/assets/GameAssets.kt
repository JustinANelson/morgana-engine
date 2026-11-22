package com.morgana.server.assets

import com.morgana.domain.game.SpriteProfile
import com.morgana.domain.game.SpriteState
import com.morgana.protocol.realms.AssetKind
import com.morgana.protocol.realms.AssetManifest
import com.morgana.protocol.realms.AssetManifestEntry
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * A game's own art, scanned from `<game>/assets/` into a content-addressed [AssetManifest].
 *
 * Layout (all PNG, file name is the numeric id the content refers to):
 * ```
 * assets/tilesets/<tilesetId>.png    width and height multiples of 32 (columns x rows of 32 x 32 tiles)
 * assets/sprites/<spriteId>.png      3 frame columns x 4 direction rows (DOWN, LEFT, RIGHT, UP): width % 3 == 0, height % 4 == 0
 * assets/icons/<iconId>.png          one square image (item icons)
 * assets/paperdolls/<id>.png         one square image drawn over the character
 * assets/projectiles/<id>.png        one square image
 * assets/animations/<id>.png         single square frame or horizontal strip of square frames (1..64) for spell/combat effects
 * assets/music/<trackId>.ogg         looping background music; <trackId> is what a map's bgmTrackId names (OGG Vorbis)
 * ```
 * Anything missing falls back to the client's procedural art, so a game may ship only the pieces it wants to replace.
 *
 * This class is pure: it reads files and validates them, nothing else. Problems are collected in [errors] (the game
 * should not start serving a broken sheet) rather than thrown one at a time, so a creator sees every mistake at once.
 */
class GameAssets private constructor(
    /** The manifest to send to clients (empty when the game ships no art). */
    val manifest: AssetManifest,
    /** Manifest path (e.g. `tilesets/1.png`) to the file on disk, for serving. */
    private val files: Map<String, Path>,
    /** Everything wrong with the folder; empty when valid. Invalid files are left out of [manifest]. */
    val errors: List<String>
) {
    val isEmpty: Boolean get() = manifest.assets.isEmpty()

    /** The file behind a manifest [path], or null when the game does not ship it. */
    fun fileFor(path: String): Path? = files[path]

    /**
     * The manifest for a client: everything for a client that understands [com.morgana.protocol.ProtocolCapabilities.ART_V2],
     * otherwise only the tilesets and sprites an older client can decode.
     */
    fun manifestFor(artV2: Boolean): AssetManifest =
        if (artV2) manifest else manifest.copy(assets = manifest.assets.filterValues { it.kind == AssetKind.TILESET || it.kind == AssetKind.SPRITE })

    /** Result of [importPng]: either [Accepted] with the saved entry & updated scan, or [Rejected] with a reason. */
    sealed interface ImportOutcome {
        data class Accepted(val entry: AssetManifestEntry, val updated: GameAssets) : ImportOutcome
        data class Rejected(val reason: String) : ImportOutcome
    }

    companion object {
        const val FOLDER = "assets"
        const val TILE_SIZE = 32
        /** Largest single file accepted; the client holds each sheet as one texture. */
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        /** Largest PNG accepted via live in-editor upload so a base64 packet fits inside the 2 MB frame cap. */
        const val MAX_UPLOAD_BYTES = 1024 * 1024
        /** Largest total for one game, so first-join download stays reasonable. */
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
        /** Largest sheet edge in pixels (a common GPU texture limit). */
        const val MAX_DIMENSION = 4096

        private val PICTURE_NAME_PATTERN = Regex("[a-zA-Z0-9_-]{1,48}")

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        private val KINDS = listOf(
            "tilesets" to AssetKind.TILESET, "sprites" to AssetKind.SPRITE, "icons" to AssetKind.ICON,
            "paperdolls" to AssetKind.PAPERDOLL, "projectiles" to AssetKind.PROJECTILE, "animations" to AssetKind.ANIMATION,
            "pictures" to AssetKind.MISC, "music" to AssetKind.MUSIC
        )
        private val MUSIC_NAME_PATTERN = Regex("[a-z0-9_]{1,48}")
        private val OGG_SIGNATURE = byteArrayOf(0x4F, 0x67, 0x67, 0x53) // "OggS"
        /** Single images (not sliced into frames): they must be square and not tiny or huge. */
        const val MIN_SINGLE = 8
        const val MAX_SINGLE = 256
        /** Maximum number of horizontal frames in an animation strip. */
        const val MAX_ANIMATION_FRAMES = 64

        val EMPTY = GameAssets(AssetManifest(gameId = ""), emptyMap(), emptyList())

        /** Scans `<gameDir>/assets`. A game with no such folder yields an empty, valid result. */
        fun scan(
            gameDir: Path,
            gameId: String,
            version: String = "1.0.0",
            sprites: SpriteProfile? = null
        ): GameAssets {
            val root = gameDir.resolve(FOLDER)
            if (!Files.isDirectory(root)) return GameAssets(AssetManifest(gameId = gameId, version = version), emptyMap(), emptyList())

            val errors = mutableListOf<String>()
            val entries = sortedMapOf<String, AssetManifestEntry>()
            val files = mutableMapOf<String, Path>()
            var total = 0L

            val known = KINDS.map { it.first }.toSet()
            Files.list(root).use { stream ->
                stream.sorted().forEach { child ->
                    if (child.fileName.toString() !in known) errors += "$FOLDER/${child.fileName}: unknown entry; expected ${known.joinToString { "$it/" }}"
                }
            }

            for ((folder, kind) in KINDS) {
                val dir = root.resolve(folder)
                if (!Files.isDirectory(dir)) continue
                val ids = mutableMapOf<Int, String>()
                val children = Files.list(dir).use { s -> s.sorted().toList() }
                for (file in children) {
                    val name = file.fileName.toString()
                    val rel = "$folder/$name"
                    val label = "$FOLDER/$rel"
                    if (!Files.isRegularFile(file)) { errors += "$label: not a file"; continue }
                    if (kind == AssetKind.MUSIC) {
                        if (!name.endsWith(".ogg") || !MUSIC_NAME_PATTERN.matches(name.removeSuffix(".ogg"))) {
                            errors += "$label: name must be <track id>.ogg (lower-case letters, digits, '_'), for example $folder/town_theme.ogg"; continue
                        }
                        val musicSize = Files.size(file)
                        if (musicSize > MAX_FILE_BYTES) { errors += "$label: $musicSize bytes exceeds the $MAX_FILE_BYTES byte limit"; continue }
                        val musicBytes = Files.readAllBytes(file)
                        if (musicBytes.size < 4 || !musicBytes.copyOfRange(0, 4).contentEquals(OGG_SIGNATURE)) { errors += "$label: not an OGG Vorbis file"; continue }
                        total += musicSize
                        entries[rel] = AssetManifestEntry(path = rel, sha256 = sha256Hex(musicBytes), sizeBytes = musicSize, kind = kind)
                        files[rel] = file
                        continue
                    }
                    val id = name.removeSuffix(".png").takeIf { name.endsWith(".png") }?.toIntOrNull()?.takeIf { it > 0 && it.toString() == name.removeSuffix(".png") }
                    if (kind != AssetKind.MISC) {
                        if (id == null) { errors += "$label: name must be <positive id>.png, for example $folder/1.png"; continue }
                        ids.put(id, name)?.let { errors += "$label: duplicates id $id already used by $folder/$it" }
                    } else if (!name.endsWith(".png") || name.removeSuffix(".png").isBlank()) {
                        errors += "$label: name must end with .png"; continue
                    }

                    val size = Files.size(file)
                    if (size > MAX_FILE_BYTES) { errors += "$label: $size bytes exceeds the $MAX_FILE_BYTES byte limit"; continue }
                    val bytes = Files.readAllBytes(file)
                    val dims = pngSize(bytes)
                    if (dims == null) { errors += "$label: not a valid PNG"; continue }
                    val (w, h) = dims
                    val problem = shapeProblem(kind, w, h, sprites)
                    if (problem != null) { errors += "$label: $problem (is ${w}x$h)"; continue }
                    total += size
                    entries[rel] = AssetManifestEntry(path = rel, sha256 = sha256Hex(bytes), sizeBytes = size, kind = kind)
                    files[rel] = file
                }
            }
            if (total > MAX_TOTAL_BYTES) errors += "$FOLDER: $total bytes in total exceeds the $MAX_TOTAL_BYTES byte limit"

            return GameAssets(AssetManifest(gameId = gameId, version = version, assets = entries), files, errors)
        }

        /** Why a sheet of this size cannot be sliced for [kind], or null when it can. */
        fun shapeProblem(kind: AssetKind, width: Int, height: Int, sprites: SpriteProfile? = null): String? {
            if (width <= 0 || height <= 0) return "has no pixels"
            if (width > MAX_DIMENSION || height > MAX_DIMENSION) return "is larger than ${MAX_DIMENSION}x$MAX_DIMENSION"
            return when (kind) {
                AssetKind.TILESET ->
                    if (width % TILE_SIZE != 0 || height % TILE_SIZE != 0) "tileset sides must be multiples of $TILE_SIZE" else null
                AssetKind.SPRITE -> when {
                    sprites != null && (width != sprites.sheetWidth || height != sprites.sheetHeight) ->
                        "sprite sheet must be ${sprites.cellWidth}x${sprites.cellHeight} x ${sprites.totalFrames} frames x ${SpriteProfile.DIRECTIONS} directions (${sprites.sheetWidth}x${sprites.sheetHeight})"
                    sprites == null && (width % 3 != 0 || height % 4 != 0) ->
                        "sprite sheet must be 3 frame columns x 4 direction rows (width divisible by 3, height by 4)"
                    else -> null
                }
                AssetKind.ICON, AssetKind.PROJECTILE ->
                    if (width != height || width < MIN_SINGLE || width > MAX_SINGLE) "image must be square, from $MIN_SINGLE to $MAX_SINGLE pixels a side" else null
                AssetKind.PAPERDOLL -> {
                    val isLegacySquare = width == height && width in MIN_SINGLE..MAX_SINGLE
                    val isDirectionalSheet = sprites != null && width == sprites.sheetWidth && height == sprites.sheetHeight
                    when {
                        isLegacySquare || isDirectionalSheet -> null
                        sprites != null ->
                            "paperdoll must be a square (${MIN_SINGLE}..$MAX_SINGLE px) or a ${sprites.sheetWidth}x${sprites.sheetHeight} directional sheet matching sprites"
                        else -> "image must be square, from $MIN_SINGLE to $MAX_SINGLE pixels a side"
                    }
                }
                AssetKind.ANIMATION -> {
                    val frames = if (height > 0 && width % height == 0) width / height else 0
                    if (height < MIN_SINGLE || height > MAX_SINGLE || frames !in 1..MAX_ANIMATION_FRAMES) {
                        "animation strip must be 1 to $MAX_ANIMATION_FRAMES square frames wide, each from $MIN_SINGLE to $MAX_SINGLE pixels"
                    } else null
                }
                else -> null
            }
        }

        /**
         * Expands a classic `3 x 4` sprite sheet PNG to match [target]'s cell geometry and state columns so a wizard-created
         * realm built from a classic template passes validation and displays taller characters immediately.
         */
        fun rescaleLegacySpriteSheet(pngBytes: ByteArray, target: SpriteProfile): ByteArray {
            val src = ImageIO.read(ByteArrayInputStream(pngBytes)) ?: return pngBytes
            val srcCellW = (src.width / 3).coerceAtLeast(1)
            val srcCellH = (src.height / SpriteProfile.DIRECTIONS).coerceAtLeast(1)
            val out = BufferedImage(target.sheetWidth, target.sheetHeight, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            try {
                // Proportional character draw box inside the target cell (e.g. 24x48 inside 32x64, or scaled cleanly).
                val drawW = target.cellWidth.coerceAtMost(srcCellW * (target.cellHeight / srcCellH.toFloat()).toInt().coerceAtLeast(1))
                    .coerceAtLeast(1)
                val drawH = target.cellHeight
                val padX = (target.cellWidth - drawW) / 2
                for (row in 0 until SpriteProfile.DIRECTIONS) {
                    var colCursor = 0
                    for (state in SpriteState.entries) {
                        val count = (target.stateFrames[state] ?: 0).coerceAtLeast(0)
                        for (i in 0 until count) {
                            val srcCol = when (state) {
                                SpriteState.IDLE -> 0
                                SpriteState.WALK -> i.coerceAtMost(2)
                                SpriteState.ATTACK -> if (i % 2 == 0) 1 else 2
                                SpriteState.CAST -> if (i % 2 == 0) 0 else 2
                                SpriteState.HARVEST -> if (i % 2 == 0) 1 else 0
                                SpriteState.JUMP -> 1
                                SpriteState.HURT -> 0
                            }.coerceIn(0, 2)
                            val yOffset = when {
                                state == SpriteState.CAST && i % 2 == 1 -> -2
                                state == SpriteState.HARVEST && i % 2 == 1 -> 2
                                else -> 0
                            }
                            val xOffset = if (state == SpriteState.ATTACK && i % 2 == 1) {
                                when (row) {
                                    1 -> -2 // LEFT
                                    2 -> 2  // RIGHT
                                    else -> 0
                                }
                            } else 0
                            val dx1 = colCursor * target.cellWidth + padX + xOffset
                            val dy1 = row * target.cellHeight + yOffset
                            val sx1 = srcCol * srcCellW
                            val sy1 = row * srcCellH
                            g.drawImage(
                                src,
                                dx1, dy1, dx1 + drawW, dy1 + drawH,
                                sx1, sy1, sx1 + srcCellW, sy1 + srcCellH,
                                null
                            )
                            colCursor++
                        }
                    }
                }
            } finally {
                g.dispose()
            }
            val bytes = ByteArrayOutputStream()
            ImageIO.write(out, "png", bytes)
            return bytes.toByteArray()
        }

        /** Width and height from a PNG's IHDR chunk, or null when [bytes] is not a PNG. */
        fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
            if (bytes.size < 24) return null
            for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return null
            // Bytes 8..11 chunk length, 12..15 "IHDR", 16..19 width, 20..23 height (big-endian).
            if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() || bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()) return null
            fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            return int(16) to int(20)
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * Validates and persists an in-editor PNG upload into `<gameDir>/assets/<kindFolder>/<id>.png`,
         * enforcing the exact [shapeProblem] and byte caps used by [scan] without leaving partial files on failure.
         */
        fun importPng(
            gameDir: Path,
            gameId: String,
            kind: AssetKind,
            assetId: String,
            bytes: ByteArray,
            sprites: SpriteProfile? = null
        ): ImportOutcome {
            val folder = KINDS.firstOrNull { it.second == kind && kind != AssetKind.MUSIC }?.first
                ?: return ImportOutcome.Rejected("Unsupported asset kind $kind")
            val trimmed = assetId.trim().removeSuffix(".png")
            val fileName = if (kind == AssetKind.MISC) {
                if (!PICTURE_NAME_PATTERN.matches(trimmed)) {
                    return ImportOutcome.Rejected("Picture name must be 1..48 letters, digits, '-' or '_'")
                }
                "$trimmed.png"
            } else {
                val parsed = trimmed.toIntOrNull()
                if (parsed == null || parsed <= 0 || parsed.toString() != trimmed) {
                    return ImportOutcome.Rejected("Asset id must be a positive whole number")
                }
                "$parsed.png"
            }
            if (bytes.isEmpty()) return ImportOutcome.Rejected("PNG file is empty")
            if (bytes.size > MAX_UPLOAD_BYTES) {
                return ImportOutcome.Rejected("PNG size (${bytes.size} bytes) exceeds the ${MAX_UPLOAD_BYTES / 1024} KB live import limit")
            }
            val dims = pngSize(bytes) ?: return ImportOutcome.Rejected("File is not a valid PNG")
            val (w, h) = dims
            val problem = shapeProblem(kind, w, h, sprites)
            if (problem != null) return ImportOutcome.Rejected("$problem (is ${w}x$h)")
            val decoded = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            if (decoded == null || decoded.width != w || decoded.height != h) {
                return ImportOutcome.Rejected("PNG image data could not be decoded")
            }

            val rel = "$folder/$fileName"
            val target = gameDir.resolve(FOLDER).resolve(folder).resolve(fileName)
            val existingSize = if (Files.exists(target)) Files.size(target) else 0L
            val currentTotal = run {
                val root = gameDir.resolve(FOLDER)
                if (!Files.isDirectory(root)) 0L
                else scan(gameDir, gameId, sprites = sprites).manifest.assets.values.sumOf { it.sizeBytes }
            }
            if (currentTotal - existingSize + bytes.size > MAX_TOTAL_BYTES) {
                return ImportOutcome.Rejected("Total assets would exceed the ${MAX_TOTAL_BYTES / (1024 * 1024)} MB game limit")
            }

            Files.createDirectories(target.parent)
            val tmp = Files.createTempFile(target.parent, ".upload-", ".png.tmp")
            try {
                Files.write(tmp, bytes)
                try {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }

            val rescanned = scan(gameDir, gameId, sprites = sprites)
            val entry = rescanned.manifest.assets[rel]
                ?: AssetManifestEntry(path = rel, sha256 = sha256Hex(bytes), sizeBytes = bytes.size.toLong(), kind = kind)
            return ImportOutcome.Accepted(entry, rescanned)
        }
    }
}
