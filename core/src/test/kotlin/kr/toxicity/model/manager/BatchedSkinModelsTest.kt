/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.toxicity.library.dynamicuv.UVModel
import kr.toxicity.library.dynamicuv.UVModelData
import kr.toxicity.library.dynamicuv.UVTextureName
import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchedSkinModelsTest {
    @Test
    fun `skin batching preserves every face tint UV and opacity including mixed and translucent runs`() {
        for (slim in listOf(false, true)) {
            for (part in VanillaPlayerModels.parts(slim = slim)) {
                verify(part.model("_batch_test", overlay = true), UVTextureName.DEFAULT)
            }
        }
    }

    @Test
    fun `armor batching preserves holes faded materials and internal joint caps`() {
        for (opacity in listOf(1f, VanillaArmorModels.FADED_OPACITY)) {
            for ((slot, bones) in VanillaArmorModels.slots) {
                for (part in VanillaPlayerModels.parts(slim = false).filter { it.name in bones }) {
                    verify(VanillaArmorModels.model(slot, part, owner = true, opacity), VanillaArmorModels.texture(owner = true, opacity))
                }
            }
        }
        for ((slot, parts) in SegmentedArmorModels.slots) for (part in parts) {
            verify(SegmentedArmorModels.model(slot, part, owner = false, 1f), UVTextureName.DEFAULT)
        }
    }

    @Test
    fun `uniform outer skin requires sixteen times fewer allocated client layers`() {
        val model = VanillaPlayerModels.parts(slim = false).first().model("_layer_count", overlay = true)
        val exported = Exported(model, UVTextureName.DEFAULT)
        for (alpha in listOf(0, 128, 255)) {
            val original = model.write(skin { _, _ -> alpha })
            val batched = BatchedSkinModels.data(original)
            val old = exported.layers(exported.original, original)
            val new = exported.layers(exported.batched, batched)
            assertEquals(385, old.capacity)
            assertEquals(25, new.capacity)
            assertEquals(if (alpha == 0) 1 else 25, new.active)
            println("Head alpha=$alpha: reserved layers ${old.capacity} -> ${new.capacity}; active ${old.active} -> ${new.active}")
        }
    }

    @Test
    fun `appended summaries preserve original data and handle partial groups`() {
        for (flags in listOf(false, true)) for (size in listOf(1, 15, 16, 17, 33)) {
            val builder = UVModelData.builder()
            for (i in 0 until size) {
                builder.colors().add(i)
                if (flags) builder.flags().add(true) else builder.floats().add(2f)
            }
            val original = builder.build()
            val batched = BatchedSkinModels.data(original)
            assertEquals(original.colors, batched.colors)
            assertEquals(original.flags, batched.flags)
            assertEquals(original.floats, batched.floats.subList(0, original.floats.size))
            assertEquals(List((size + 15) / 16) { 2f }, batched.floats.drop(original.floats.size))
        }
    }

    private fun verify(model: UVModel, texture: UVTextureName) {
        val exported = Exported(model, texture)
        val random = Random(613)
        val patterns = listOf<(Int, Int) -> Int>(
            { _, _ -> 0 }, { _, _ -> 128 }, { _, _ -> 255 },
            { x, y -> if ((x + y) % 2 == 0) 255 else 0 },
            { x, _ -> if (x < 32) 0 else 255 },
            { _, _ -> listOf(0, 128, 255)[random.nextInt(3)] }
        )
        for ((index, pattern) in patterns.withIndex()) {
            val original = model.write(skin(pattern))
            val batched = BatchedSkinModels.data(original)
            assertEquals(
                exported.faces(exported.original, original),
                exported.faces(exported.batched, batched),
                "${model.modelName()} pattern=$index"
            )
            assertTrue(exported.layers(exported.batched, batched).active <= exported.layers(exported.original, original).active)
        }
    }

    private fun skin(alpha: (Int, Int) -> Int) = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until width) setRGB(x, y, (alpha(x, y) shl 24) or (x shl 16) or (y shl 8) or 0x37)
    }

    private data class Layers(var active: Int = 0, var capacity: Int = 1) {
        fun reserve(count: Int) { capacity = maxOf(capacity, active + count) }
    }

    private class Exported(model: UVModel, texture: UVTextureName) {
        private val resources = BatchedSkinModels.export(model, texture).associate { it.path() to JsonParser.parseString(it.build().toString(Charsets.UTF_8)).asJsonObject }
        private val base = model.itemModelNamespace().let { "assets/${it.substringBefore(':')}/items/${it.substringAfter(':')}" }
        val original = resources.getValue("$base.json").getAsJsonObject("model")
        val batched = resources.getValue("${base}_batched.json").getAsJsonObject("model")

        fun layers(node: JsonObject, data: UVModelData): Layers = Layers().apply {
            visit(node, data, this) { }
        }

        fun faces(node: JsonObject, data: UVModelData): List<String> = buildList {
            visit(node, data, Layers()) { reference ->
                val id = reference.get("model").asString
                val geometry = resources.getValue("assets/${id.substringBefore(':')}/models/${id.substringAfter(':')}.json")
                val tints = reference.getAsJsonArray("tints")
                for (element in geometry.getAsJsonArray("elements")) {
                    val copy = element.deepCopy().asJsonObject
                    for (entry in copy.getAsJsonObject("faces").entrySet()) {
                        val face = entry.value.asJsonObject
                        val tint = tints[face.get("tintindex").asInt].asJsonObject
                        face.addProperty("tintindex", data.colors.getInt(tint.get("index").asInt))
                        val texture = face.get("texture").asString.removePrefix("#")
                        face.addProperty("texture", geometry.getAsJsonObject("textures").get(texture).asString)
                    }
                    add(copy.toString())
                }
            }
        }

        private fun visit(node: JsonObject, data: UVModelData, layers: Layers, leaf: (JsonObject) -> Unit) {
            when (node.get("type").asString.removePrefix("minecraft:")) {
                "empty" -> Unit
                "model" -> { layers.reserve(1); layers.active++; leaf(node) }
                "composite" -> {
                    val children = node.getAsJsonArray("models")
                    // Mirrors 26.3 CompositeModel.update / ItemStackRenderState.ensureCapacity.
                    layers.reserve(children.size())
                    children.forEach { visit(it.asJsonObject, data, layers, leaf) }
                }
                "condition" -> visit(node.getAsJsonObject(if (data.flags.getBoolean(node.get("index").asInt)) "on_true" else "on_false"), data, layers, leaf)
                "range_dispatch" -> {
                    val value = data.floats.getFloat(node.get("index").asInt)
                    val selected = node.getAsJsonArray("entries").lastOrNull { it.asJsonObject.get("threshold").asFloat <= value }
                    visit(selected?.asJsonObject?.getAsJsonObject("model") ?: node.getAsJsonObject("fallback"), data, layers, leaf)
                }
                else -> error("Unknown model node $node")
            }
        }
    }
}
