/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import kr.toxicity.library.dynamicuv.UVByteBuilder
import kr.toxicity.library.dynamicuv.UVModel
import kr.toxicity.library.dynamicuv.UVTextureName
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Builds fork avatar resources using DynamicUV's public constructor rather than its internal factories. */
internal object AvatarPackResources {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /** Scope geometry names to the avatar variant: identical geometry can use different audience textures and materials. */
    fun export(model: UVModel, texture: UVTextureName = UVTextureName.DEFAULT): List<UVByteBuilder> {
        val indices = linkedMapOf<String, Int>()
        return model.asJson(texture) { identifier ->
            model.modelName() + "_" + indices.getOrPut(identifier) { indices.size }
        }.toList()
    }

    fun bytes(json: JsonElement): ByteArray = gson.toJson(json).toByteArray(Charsets.UTF_8)

    fun json(path: String, estimatedSize: Long, json: JsonElement): UVByteBuilder = UVByteBuilder(path, estimatedSize) {
        bytes(json)
    }

    fun texture(path: String, image: BufferedImage): UVByteBuilder = UVByteBuilder(path, 4L * image.width * image.height) {
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "PNG encoder unavailable" }
            output.toByteArray()
        }
    }
}
