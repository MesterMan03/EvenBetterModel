/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import javax.imageio.ImageIO

/** Owns the native-normalized pixels shared by model colors and the optional PNG export. */
internal class SkinTexture(source: BufferedImage) {

    val image: BufferedImage = normalize(source)

    private val encoded by lazy {
        try {
            ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output)) { "No PNG encoder is available for the player skin" }
                output.toByteArray()
            }
        } catch (exception: IOException) {
            throw UncheckedIOException("Unable to encode the resolved player skin", exception)
        }
    }

    fun png(): ByteArray = encoded.clone()

    private companion object {
        fun normalize(source: BufferedImage): BufferedImage {
            require(source.width == 64 && (source.height == 32 || source.height == 64)) {
                "Player skin must be 64 by 32 or 64 by 64 pixels, got ${source.width} by ${source.height}"
            }
            val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, 64, source.height, source.getRGB(0, 0, 64, source.height, null, 0, 64), 0, 64)
            val legacy = source.height == 32
            if (legacy) {
                // Match the native legacy conversion: mirror each face and exchange the side faces.
                fun mirror(sourceX: Int, sourceY: Int, targetX: Int, targetY: Int, height: Int) {
                    repeat(4) { x ->
                        repeat(height) { y ->
                            image.setRGB(targetX + 3 - x, targetY + y, source.getRGB(sourceX + x, sourceY + y))
                        }
                    }
                }
                mirror(4, 16, 20, 48, 4)
                mirror(8, 16, 24, 48, 4)
                mirror(0, 20, 24, 52, 12)
                mirror(4, 20, 20, 52, 12)
                mirror(8, 20, 16, 52, 12)
                mirror(12, 20, 28, 52, 12)
                mirror(44, 16, 36, 48, 4)
                mirror(48, 16, 40, 48, 4)
                mirror(40, 20, 40, 52, 12)
                mirror(44, 20, 36, 52, 12)
                mirror(48, 20, 32, 52, 12)
                mirror(52, 20, 44, 52, 12)
            }
            fun opaque(minX: Int, minY: Int, maxX: Int, maxY: Int) {
                for (x in minX..<maxX) for (y in minY..<maxY) {
                    image.setRGB(x, y, image.getRGB(x, y) or -0x1000000)
                }
            }
            opaque(0, 0, 32, 16)
            // Old skins without any translucent pixels in this region used an unused opaque hat area.
            // The client clears that area only for legacy skins, then restores the body base alpha.
            if (legacy && (32..<64).all { x -> (0..<32).all { y -> image.getRGB(x, y) ushr 24 >= 128 } }) {
                for (x in 32..<64) for (y in 0..<32) {
                    image.setRGB(x, y, image.getRGB(x, y) and 0xFFFFFF)
                }
            }
            opaque(0, 16, 64, 32)
            opaque(16, 48, 48, 64)
            return image
        }
    }
}
