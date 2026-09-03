/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import kr.toxicity.library.dynamicuv.UVPos
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentedSkinUVTest {

    @Test
    fun `bottom cap averages the four seam edges`() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val uv = SegmentedSkinUV(UVPos(0, 8), UVPos(20, 8), 4, 4)

        fillBoundary(image, UVPos(0, 8), 9, RED, BLUE, GREEN, WHITE, 4, 4)
        fillBoundary(image, UVPos(20, 8), 9, RED, TRANSPARENT_BLUE, RED, TRANSPARENT_BLUE, 4, 4)

        val result = uv.closeSegment(image, startY = 0, height = 2, closeBottom = true)

        assertCap(result, UVPos(8, 4), 4, 4, -0x7f7f80)
        assertCap(result, UVPos(28, 4), 4, 4, -0x7f010000)
    }

    @Test
    fun `top cap uses the first row of a slim segment`() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val uv = SegmentedSkinUV(UVPos(0, 8), UVPos(20, 8), 3, 4)

        fillBoundary(image, UVPos(0, 8), 10, BASE_COLOR, BASE_COLOR, BASE_COLOR, BASE_COLOR, 3, 4)
        fillBoundary(image, UVPos(20, 8), 10, OVERLAY_COLOR, OVERLAY_COLOR, OVERLAY_COLOR, OVERLAY_COLOR, 3, 4)

        val result = uv.closeSegment(image, startY = 2, height = 2, closeTop = true)

        assertCap(result, UVPos(4, 4), 3, 4, BASE_COLOR)
        assertCap(result, UVPos(24, 4), 3, 4, OVERLAY_COLOR)
        assertEquals(0, result.getRGB(7, 4))
    }

    private fun fillBoundary(
        image: BufferedImage,
        sideOrigin: UVPos,
        y: Int,
        east: Int,
        north: Int,
        west: Int,
        south: Int,
        width: Int,
        depth: Int
    ) {
        repeat(depth) { x ->
            image.setRGB(sideOrigin.x + x, y, east)
            image.setRGB(sideOrigin.x + depth + width + x, y, west)
        }
        repeat(width) { x ->
            image.setRGB(sideOrigin.x + depth + x, y, north)
            image.setRGB(sideOrigin.x + (depth * 2) + width + x, y, south)
        }
    }

    private fun assertCap(image: BufferedImage, origin: UVPos, width: Int, depth: Int, color: Int) {
        repeat(width) { x ->
            repeat(depth) { z ->
                assertEquals(color, image.getRGB(origin.x + x, origin.z + z))
            }
        }
    }

    private companion object {
        const val RED = -0x10000
        const val GREEN = -0xff0100
        const val BLUE = -0xffff01
        const val WHITE = -0x1
        const val TRANSPARENT_BLUE = 0x000000FF
        const val BASE_COLOR = -0xedcbaa
        const val OVERLAY_COLOR = -0x543211
    }
}
