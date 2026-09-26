/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.toxicity.library.dynamicuv.*
import kr.toxicity.model.api.util.TransformedItemStack
import kr.toxicity.model.util.PLATFORM
import java.awt.image.BufferedImage
import java.util.Collections
import kotlin.math.abs

/**
 * Rigid player geometry, separate from the segmented cinematic skin rig.
 * Coordinates use model pixels after flipping native X/Y: +X right, +Y up, -Z forward.
 * DynamicUV retains its per-texel faces to support arbitrary skins without rebuilding the pack.
 */
internal object VanillaPlayerModels {
    private val namespace = UVNamespace("chronovale", "player_avatar")
    private val observerTexture = UVTextureName("observer_pixel", "observer_translucent_pixel")
    private val ownerTexture = UVTextureName("owner_pixel", "owner_translucent_pixel")
    private val handTexture = UVTextureName("hand_pixel", "hand_translucent_pixel")
    // Reserved texture RGB, decoded before skin tinting by ChronoCore's item shader.
    const val OBSERVER_RGB = 0xFCF9FB
    const val OWNER_RGB = 0xFCF9FD
    const val HAND_RGB = 0xFCF9FC

    internal data class Part(
        val name: String,
        val width: Int,
        val height: Int,
        val depth: Int,
        val x: Float,
        val y: Float,
        val baseU: Int,
        val baseV: Int,
        val overlayU: Int,
        val overlayV: Int,
        val layerBit: Int,
        val inflation: Float = 0.25f
    ) {
        fun model(suffix: String, overlay: Boolean): UVModel {
            val model = UVModel(namespace, name + suffix)
            fun element(u: Int, v: Int, inflate: Float, type: UVElement.ColorType) = UVElement(
                ElementVector(width.toFloat(), height.toFloat(), depth.toFloat()).div(16f).inflate(inflate),
                ElementVector(x, y, 0f).div(16f),
                UVSpace(width, height, depth), type,
                mapOf(
                    UVFace.EAST to UVPos(u, v + depth),
                    UVFace.NORTH to UVPos(u + depth, v + depth),
                    UVFace.WEST to UVPos(u + depth + width, v + depth),
                    UVFace.SOUTH to UVPos(u + depth * 2 + width, v + depth),
                    UVFace.UP to UVPos(u + depth, v),
                    UVFace.DOWN to UVPos(u + depth + width, v)
                )
            )
            model.addElement(element(baseU, baseV, 0f, UVElement.ColorType.RGB))
            if (overlay) model.addElement(element(overlayU, overlayV, inflation, UVElement.ColorType.COMPLEX_ARGB))
            return model
        }
    }

    internal fun parts(slim: Boolean): List<Part> = listOf(
        Part("head", 8, 8, 8, 0f, 4f, 0, 0, 32, 0, 64, 0.5f),
        Part("body", 8, 12, 4, 0f, -6f, 16, 16, 16, 32, 2),
        Part("right_arm", if (slim) 3 else 4, 12, 4, if (slim) 0.5f else 1f, -4f, 40, 16, 40, 32, 8),
        Part("left_arm", if (slim) 3 else 4, 12, 4, if (slim) -0.5f else -1f, -4f, 32, 48, 48, 48, 4),
        Part("right_leg", 4, 12, 4, 0f, -6f, 0, 16, 0, 32, 32),
        Part("left_leg", 4, 12, 4, 0f, -6f, 16, 48, 0, 48, 16)
    )

    private data class Key(val slim: Boolean, val overlay: Boolean, val owner: Boolean)
    private val models = buildMap {
        for (slim in listOf(false, true)) for (overlay in listOf(false, true)) for (owner in listOf(false, true)) {
            val suffix = "_${if (slim) "slim" else "classic"}_${if (overlay) "layered" else "base"}${if (owner) "_owner" else ""}"
            put(
                Key(slim = slim, overlay = overlay, owner = owner),
                parts(slim = slim).associate { it.name to it.model(suffix, overlay = overlay) }
            )
        }
    }
    private val handModels = buildMap {
        for (slim in listOf(false, true)) for (overlay in listOf(false, true)) {
            val suffix = "_${if (slim) "slim" else "classic"}_${if (overlay) "layered" else "base"}_hand"
            put(
                slim to overlay,
                parts(slim = slim).filter { it.name.endsWith("_arm") }.associate { it.name to it.model(suffix, overlay = overlay) }
            )
        }
    }

    fun write(block: (UVByteBuilder) -> Unit) {
        models.forEach { (key, parts) ->
            parts.values.forEach { model ->
                BatchedSkinModels.export(model, if (key.owner) ownerTexture else observerTexture).forEach {
                    block(it.withMetricUVs(forceTranslucent = key.owner))
                }
            }
        }
        handModels.values.forEach { parts ->
            parts.values.forEach { model -> BatchedSkinModels.export(model, handTexture).forEach { block(it.withMetricUVs(forceTranslucent = true)) } }
        }
        for ((name, alpha, rgb) in listOf(
            Triple("observer_pixel", 255, OBSERVER_RGB), Triple("observer_translucent_pixel", 128, OBSERVER_RGB),
            Triple("owner_pixel", 255, OWNER_RGB), Triple("owner_translucent_pixel", 128, OWNER_RGB),
            Triple("hand_pixel", 255, HAND_RGB), Triple("hand_translucent_pixel", 128, HAND_RGB)
        )) {
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 16) for (x in 0 until 16) image.setRGB(x, y, (alpha shl 24) or rgb)
            block(UVByteBuilder.of(namespace.texture(name), image))
        }
    }

    /**
     * One atlas texel represents one local model pixel, including inflated outer layers.
     * Fragment derivatives then recover the display's continuous scale without modifying skin
     * tints or selecting thousands of texture variants. The 26.3 cuboid baker maps UVs linearly;
     * the inset keeps every face inside the uniform 16x16 material marker at all mip levels.
     * Owner materials enter the translucent passes so ChronoCore can read its eye-camera probe;
     * the material flag preserves the original opacity, tint data, and observer rendering.
     */
    internal fun UVByteBuilder.withMetricUVs(forceTranslucent: Boolean = false): UVByteBuilder {
        if (!path().contains("/models/")) return this
        return UVByteBuilder.of(path(), estimatedSize()) {
            val json = JsonParser.parseString(build().toString(Charsets.UTF_8)).asJsonObject
            if (forceTranslucent) {
                val textures = json.getAsJsonObject("textures")
                for ((slot, texture) in textures.entrySet().toList()) {
                    if (texture.isJsonPrimitive && !texture.asString.startsWith('#')) {
                        textures.add(slot, JsonObject().apply {
                            addProperty("sprite", texture.asString)
                            addProperty("force_translucent", true)
                        })
                    }
                }
            }
            json.getAsJsonArray("elements").forEach { element ->
                val cube = element.asJsonObject
                val from = cube.getAsJsonArray("from")
                val to = cube.getAsJsonArray("to")
                val size = (0..2).map { abs(to[it].asFloat - from[it].asFloat) }
                cube.getAsJsonObject("faces").entrySet().forEach { (direction, face) ->
                    val (width, height) = when (direction) {
                        "east", "west" -> size[2] to size[1]
                        "up", "down" -> size[0] to size[2]
                        else -> size[0] to size[1]
                    }
                    // Segmented armor joint caps reach 10px; UV4..14 still leaves a two-pixel marker inset.
                    check(width > 0f && height > 0f && width <= 10f && height <= 10f) { "Invalid avatar face metric" }
                    face.asJsonObject.add(
                        "uv",
                        JsonArray(4).apply {
                            add(4f)
                            add(4f)
                            add(4f + width)
                            add(4f + height)
                        }
                    )
                }
            }
            UVByteBuilder.GSON.toJson(json).toByteArray(Charsets.UTF_8)
        }
    }

    class Skin(private val slim: Boolean, image: BufferedImage) {
        // Owner models have identical data layouts. Generate colors once for each base/layered part.
        private val data = listOf(false, true).associateWith { overlay ->
            models.getValue(Key(slim = slim, overlay = overlay, owner = false)).mapValues { BatchedSkinModels.data(it.value.write(image)) }
        }

        fun items(skinParts: Int, cameraOwner: Boolean): Map<String, TransformedItemStack> = Collections.unmodifiableMap(
            parts(slim = slim).associate { part ->
                val overlay = skinParts and part.layerBit != 0
                val model = models.getValue(Key(slim = slim, overlay = overlay, owner = cameraOwner)).getValue(part.name)
                val data = data.getValue(overlay).getValue(part.name)
                part.name to PLATFORM.nms().createSkinItem(BatchedSkinModels.itemModel(model), data.floats, data.flags, emptyList(), data.colors)
            }
        )

        fun arms(skinParts: Int): Map<String, TransformedItemStack> = Collections.unmodifiableMap(
            parts(slim = slim).filter { it.name.endsWith("_arm") }.associate { part ->
                val overlay = skinParts and part.layerBit != 0
                val model = handModels.getValue(slim to overlay).getValue(part.name)
                val data = data.getValue(overlay).getValue(part.name)
                part.name to PLATFORM.nms().createSkinItem(BatchedSkinModels.itemModel(model), data.floats, data.flags, emptyList(), data.colors)
            }
        )
    }
}
