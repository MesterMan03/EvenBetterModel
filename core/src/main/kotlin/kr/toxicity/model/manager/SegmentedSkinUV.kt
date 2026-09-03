/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import kr.toxicity.library.dynamicuv.UVPos
import java.awt.image.BufferedImage

internal class SegmentedSkinUV(
    private val baseSideOrigin: UVPos,
    private val overlaySideOrigin: UVPos,
    private val width: Int,
    private val depth: Int
) {
    fun closeSegment(
        image: BufferedImage,
        startY: Int,
        height: Int,
        closeTop: Boolean = false,
        closeBottom: Boolean = false
    ): BufferedImage {
        val caps = buildList {
            if (closeTop) {
                add(cap(baseSideOrigin, startY, top = true, preserveAlpha = false))
                add(cap(overlaySideOrigin, startY, top = true, preserveAlpha = true))
            }
            if (closeBottom) {
                add(cap(baseSideOrigin, startY + height - 1, top = false, preserveAlpha = false))
                add(cap(overlaySideOrigin, startY + height - 1, top = false, preserveAlpha = true))
            }
        }
        return image.withJointCaps(caps)
    }

    private fun cap(sideOrigin: UVPos, boundaryOffset: Int, top: Boolean, preserveAlpha: Boolean) = JointCap(
        destination = UVPos(
            sideOrigin.x + depth + if (top) 0 else width,
            sideOrigin.z - depth
        ),
        sideOrigin = sideOrigin,
        boundaryY = sideOrigin.z + boundaryOffset,
        width = width,
        depth = depth,
        preserveAlpha = preserveAlpha
    )
}

private data class JointCap(
    private val destination: UVPos,
    private val sideOrigin: UVPos,
    private val boundaryY: Int,
    private val width: Int,
    private val depth: Int,
    private val preserveAlpha: Boolean
) {
    fun paint(source: BufferedImage, target: BufferedImage) {
        var alphaTotal = 0L
        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        var colorWeight = 0L
        var sampleCount = 0

        fun sample(x: Int, y: Int) {
            val color = source.getRGB(x, y)
            val alpha = color ushr 24 and 0xFF
            val weight = if (preserveAlpha) alpha else 1
            alphaTotal += alpha
            redTotal += (color ushr 16 and 0xFF) * weight.toLong()
            greenTotal += (color ushr 8 and 0xFF) * weight.toLong()
            blueTotal += (color and 0xFF) * weight.toLong()
            colorWeight += weight
            sampleCount++
        }

        repeat(depth) { x ->
            sample(sideOrigin.x + x, boundaryY)
            sample(sideOrigin.x + depth + width + x, boundaryY)
        }
        repeat(width) { x ->
            sample(sideOrigin.x + depth + x, boundaryY)
            sample(sideOrigin.x + (depth * 2) + width + x, boundaryY)
        }

        fun average(total: Long) = if (colorWeight == 0L) 0 else ((total + colorWeight / 2) / colorWeight).toInt()

        val alpha = if (preserveAlpha) ((alphaTotal + sampleCount / 2) / sampleCount).toInt() else 0xFF
        val color =
            (alpha shl 24) or
                (average(redTotal) shl 16) or
                (average(greenTotal) shl 8) or
                average(blueTotal)
        repeat(width) { x ->
            repeat(depth) { z ->
                target.setRGB(destination.x + x, destination.z + z, color)
            }
        }
    }
}

private fun BufferedImage.withJointCaps(caps: List<JointCap>) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { result ->
    result.createGraphics().let {
        it.drawImage(this, 0, 0, null)
        it.dispose()
    }
    caps.forEach { it.paint(this, result) }
}
