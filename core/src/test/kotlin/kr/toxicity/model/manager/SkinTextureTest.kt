/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class SkinTextureTest {

    @Test
    fun `modern base layers become opaque without losing outer alpha or RGB`() {
        val source = patternedImage(64)
        val normalized = SkinTexture(source).image

        repeat(64) { x ->
            repeat(64) { y ->
                val base = (x < 32 && y < 16) || y in 16..<32 || (x in 16..<48 && y >= 48)
                val sourceColor = source.getRGB(x, y)
                val expected = if (base) sourceColor or -0x1000000 else sourceColor
                assertEquals(expected, normalized.getRGB(x, y), "Pixel ($x, $y)")
            }
        }
        assertEquals(0x0020045A, normalized.getRGB(32, 4), "Transparent outer-layer RGB must survive")
    }

    @Test
    fun `legacy left limb faces mirror and swap their side faces like the native client`() {
        val source = patternedImage(32)
        val normalized = SkinTexture(source).image
        // Source and destination origins for the native six-face right-to-left limb mapping.
        val faces = listOf(
            intArrayOf(4, 16, 20, 48, 4),
            intArrayOf(8, 16, 24, 48, 4),
            intArrayOf(0, 20, 24, 52, 12),
            intArrayOf(4, 20, 20, 52, 12),
            intArrayOf(8, 20, 16, 52, 12),
            intArrayOf(12, 20, 28, 52, 12),
            intArrayOf(44, 16, 36, 48, 4),
            intArrayOf(48, 16, 40, 48, 4),
            intArrayOf(40, 20, 40, 52, 12),
            intArrayOf(44, 20, 36, 52, 12),
            intArrayOf(48, 20, 32, 52, 12),
            intArrayOf(52, 20, 44, 52, 12)
        )
        for ((sourceX, sourceY, targetX, targetY, height) in faces) {
            repeat(4) { x ->
                repeat(height) { y ->
                    assertEquals(
                        source.getRGB(sourceX + 3 - x, sourceY + y) or -0x1000000,
                        normalized.getRGB(targetX + x, targetY + y),
                        "Legacy face at ($targetX, $targetY), pixel ($x, $y)"
                    )
                }
            }
        }
        assertEquals(0, normalized.getRGB(4, 52), "Legacy left leg has no outer layer")
        assertEquals(0, normalized.getRGB(52, 52), "Legacy left arm has no outer layer")
        assertEquals(0, normalized.getRGB(20, 36), "Legacy torso has no outer layer")
    }

    @Test
    fun `legacy opaque hat is cleared but body base is restored`() {
        val source = filledLegacy(0x80123456.toInt())
        val normalized = SkinTexture(source).image

        assertEquals(0x00123456, normalized.getRGB(40, 4))
        assertEquals(0xFF123456.toInt(), normalized.getRGB(40, 24))
        assertEquals(0xFF123456.toInt(), normalized.getRGB(4, 4))
        assertEquals(0x80123456.toInt(), source.getRGB(40, 4), "Normalization must not mutate its input")
    }

    @Test
    fun `legacy authored hat alpha below the native threshold preserves the outer layer`() {
        val source = filledLegacy(0xFF123456.toInt())
        source.setRGB(63, 31, 0x7F654321)
        source.setRGB(40, 4, 0xC0123456.toInt())
        val normalized = SkinTexture(source).image

        assertEquals(0xC0123456.toInt(), normalized.getRGB(40, 4))
        assertEquals(0xFF123456.toInt(), normalized.getRGB(41, 4))
        assertEquals(0xFF654321.toInt(), normalized.getRGB(63, 31))
    }

    @Test
    fun `export is a snapshot encoded as RGBA PNG and each caller receives its own bytes`() {
        val source = patternedImage(64)
        val texture = SkinTexture(source)
        val expectedPixels = texture.image.getRGB(0, 0, 64, 64, null, 0, 64)
        source.setRGB(32, 4, -1)

        val exported = texture.png()
        val originalExport = exported.clone()
        assertEquals(6, exported[25].toInt(), "PNG IHDR color type must be RGBA")
        val decoded = ImageIO.read(ByteArrayInputStream(exported))
        assertEquals(64, decoded.width)
        assertEquals(64, decoded.height)
        assertContentEquals(expectedPixels, decoded.getRGB(0, 0, 64, 64, null, 0, 64))

        exported.fill(0)
        val nextExport = texture.png()
        assertNotSame(exported, nextExport)
        assertContentEquals(originalExport, nextExport)
    }

    @Test
    fun `unsupported skin dimensions are rejected`() {
        for ((width, height) in listOf(32 to 32, 64 to 48, 128 to 64, 128 to 128)) {
            assertFailsWith<IllegalArgumentException> {
                SkinTexture(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))
            }
        }
    }

    private fun patternedImage(height: Int) = BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB).apply {
        repeat(64) { x ->
            repeat(height) { y ->
                val alpha = when ((x + y) % 3) {
                    0 -> 0
                    1 -> 73
                    else -> 255
                }
                setRGB(x, y, (alpha shl 24) or (x shl 16) or (y shl 8) or 0x5A)
            }
        }
    }

    private fun filledLegacy(color: Int) = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, 64, 32, IntArray(64 * 32) { color }, 0, 64)
    }
}
