package com.morgana.client.editor

import com.morgana.domain.game.SpriteProfile
import com.morgana.protocol.realms.AssetKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtImportValidatorTest {

    private fun fakePng(width: Int, height: Int): ByteArray {
        val out = ByteArray(24)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        sig.copyInto(out, 0)
        out[12] = 'I'.code.toByte()
        out[13] = 'H'.code.toByte()
        out[14] = 'D'.code.toByte()
        out[15] = 'R'.code.toByte()
        fun writeInt(offset: Int, value: Int) {
            out[offset] = ((value ushr 24) and 0xFF).toByte()
            out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            out[offset + 3] = (value and 0xFF).toByte()
        }
        writeInt(16, width)
        writeInt(20, height)
        return out
    }

    @Test
    fun `pngSize parses IHDR dimensions and rejects non-PNG headers`() {
        assertEquals(160 to 32, ArtImportValidator.pngSize(fakePng(160, 32)))
        assertNull(ArtImportValidator.pngSize(byteArrayOf(1, 2, 3)))
        assertNull(ArtImportValidator.pngSize("not a png file at all!".encodeToByteArray()))
    }

    @Test
    fun `validatePngUpload enforces shape and id rules per asset kind`() {
        assertNull(ArtImportValidator.validatePngUpload(AssetKind.ICON, "12", fakePng(32, 32)))
        assertNull(ArtImportValidator.validatePngUpload(AssetKind.TILESET, "1", fakePng(256, 96)))
        assertNull(ArtImportValidator.validatePngUpload(AssetKind.ANIMATION, "3", fakePng(160, 32)))
        assertNull(ArtImportValidator.validatePngUpload(AssetKind.SPRITE, "5", fakePng(96, 128), null))
        assertNull(
            ArtImportValidator.validatePngUpload(
                AssetKind.SPRITE,
                "5",
                fakePng(SpriteProfile.MODERN_TALL.sheetWidth, SpriteProfile.MODERN_TALL.sheetHeight),
                SpriteProfile.MODERN_TALL
            )
        )
        assertNull(
            ArtImportValidator.validatePngUpload(
                AssetKind.PAPERDOLL,
                "101",
                fakePng(SpriteProfile.MODERN_TALL.sheetWidth, SpriteProfile.MODERN_TALL.sheetHeight),
                SpriteProfile.MODERN_TALL
            )
        )
        assertNull(ArtImportValidator.validatePngUpload(AssetKind.PAPERDOLL, "102", fakePng(32, 32), SpriteProfile.MODERN_TALL))
        assertNotNull(ArtImportValidator.validatePngUpload(AssetKind.PAPERDOLL, "103", fakePng(96, 128), SpriteProfile.MODERN_TALL))
        assertNotNull(ArtImportValidator.validatePngUpload(AssetKind.PAPERDOLL, "104", fakePng(96, 128), null))
        assertNull(ArtImportValidator.validatePngUpload(AssetKind.MISC, "boss_crest", fakePng(120, 90)))

        assertNotNull(ArtImportValidator.validatePngUpload(AssetKind.ICON, "0", fakePng(32, 32)))
        assertNotNull(ArtImportValidator.validatePngUpload(AssetKind.ICON, "../1", fakePng(32, 32)))
        assertNotNull(ArtImportValidator.validatePngUpload(AssetKind.ICON, "1", fakePng(32, 16)))
        assertNotNull(ArtImportValidator.validatePngUpload(AssetKind.TILESET, "1", fakePng(30, 32)))
        assertNotNull(ArtImportValidator.validatePngUpload(AssetKind.ANIMATION, "1", fakePng(100, 32)))
        assertNotNull(ArtImportValidator.validatePngUpload(AssetKind.SPRITE, "1", fakePng(96, 128), SpriteProfile.MODERN_TALL))

        val tooBig = ByteArray(ArtImportValidator.MAX_UPLOAD_BYTES + 1)
        val sizeErr = ArtImportValidator.validatePngUpload(AssetKind.ICON, "1", tooBig)
        assertTrue(sizeErr != null && "exceeds" in sizeErr, sizeErr.toString())
    }
}

