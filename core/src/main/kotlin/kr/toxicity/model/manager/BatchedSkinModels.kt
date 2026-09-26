/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import it.unimi.dsi.fastutil.floats.FloatArrayList
import it.unimi.dsi.fastutil.floats.FloatImmutableList
import kr.toxicity.library.dynamicuv.UVByteBuilder
import kr.toxicity.library.dynamicuv.UVModel
import kr.toxicity.library.dynamicuv.UVModelData
import kr.toxicity.library.dynamicuv.UVTextureName

/**
 * DynamicUV's alpha pixels normally each reserve a client render layer, even when transparent.
 * Uniform runs share one geometry/tint layer; mixed runs retain the original lossless branches.
 * The original item definition and data indices remain usable by already spawned displays.
 */
internal object BatchedSkinModels {
    private const val GROUP_SIZE = 16
    private const val MIXED = 3f
    private const val SUFFIX = "_batched"

    fun itemModel(model: UVModel): String = model.itemModelNamespace() + SUFFIX

    /** Append opacity summaries without changing the original per-pixel colors, flags or floats. */
    fun data(source: UVModelData): UVModelData {
        require(source.flags.isEmpty() || source.floats.isEmpty()) { "Mixed alpha encodings are not supported" }
        val count = if (source.flags.isEmpty()) source.floats.size else source.flags.size
        if (count == 0) return source
        val floats = FloatArrayList(source.floats)
        for (start in 0 until count step GROUP_SIZE) {
            fun alpha(index: Int): Float = if (source.flags.isEmpty()) source.floats.getFloat(index) else if (source.flags.getBoolean(index)) 2f else 0f
            val first = alpha(start)
            floats.add(if ((start + 1 until minOf(start + GROUP_SIZE, count)).all { alpha(it) == first }) first else MIXED)
        }
        return UVModelData(FloatImmutableList(floats), source.flags, source.colors)
    }

    fun export(model: UVModel, texture: UVTextureName): List<UVByteBuilder> {
        val resources = model.asJson(texture)
        val item = resources.single { it.path().contains("/items/") }
        val original = json(item)
        val children = original.getAsJsonObject("model").getAsJsonArray("models").map { it.asJsonObject }
        val alpha = children.filter { it.get("property")?.asString == "minecraft:custom_model_data" }
        val flagEncoding = alpha.firstOrNull()?.get("type")?.asString == "minecraft:condition"
        require(alpha.all { (it.get("type").asString == "minecraft:condition") == flagEncoding })
        require(alpha.map { it.get("index").asInt } == alpha.indices.toList()) { "Expected contiguous alpha indices" }
        val geometry = resources.filter { it.path().contains("/models/") }.associateBy { resource ->
            val path = resource.path().removePrefix("assets/")
            path.substringBefore('/') + ":" + path.substringAfter("/models/").removeSuffix(".json")
        }
        val added = mutableListOf<UVByteBuilder>()
        fun batch(nodes: List<JsonObject>, group: Int, translucent: Boolean): JsonObject {
            val references = nodes.map { node ->
                if (flagEncoding) node.getAsJsonObject("on_true")
                else node.getAsJsonArray("entries")[if (translucent) 0 else 1].asJsonObject.getAsJsonObject("model")
            }
            val elements = JsonArray()
            val tints = JsonArray()
            var merged: JsonObject? = null
            for (reference in references) {
                val source = json(geometry.getValue(reference.get("model").asString))
                if (merged == null) merged = source.deepCopy().apply { add("elements", elements) }
                require(source.get("textures") == merged.get("textures")) { "A batch must share its texture" }
                for (element in source.getAsJsonArray("elements")) {
                    val copy = element.deepCopy().asJsonObject
                    for (face in copy.getAsJsonObject("faces").entrySet()) {
                        val value = face.value.asJsonObject
                        value.get("tintindex")?.let { value.addProperty("tintindex", it.asInt + tints.size()) }
                    }
                    elements.add(copy)
                }
                tints.addAll(reference.getAsJsonArray("tints"))
            }
            val name = itemModel(model) + "_group_${group}_${if (translucent) "translucent" else "opaque"}"
            val path = "assets/${name.substringBefore(':')}/models/${name.substringAfter(':')}.json"
            added += UVByteBuilder.of(path, elements.size() * 256L, requireNotNull(merged))
            return JsonObject().apply {
                addProperty("type", "minecraft:model")
                addProperty("model", name)
                add("tints", tints)
            }
        }
        val grouped = alpha.chunked(GROUP_SIZE).mapIndexed { index, nodes ->
            JsonObject().apply {
                addProperty("type", "minecraft:range_dispatch")
                addProperty("property", "minecraft:custom_model_data")
                addProperty("index", (if (flagEncoding) 0 else alpha.size) + index)
                // Keep the original per-pixel branches for values outside the summary protocol.
                add("fallback", composite(nodes))
                add("entries", JsonArray().apply {
                    add(entry(0, JsonObject().apply { addProperty("type", "minecraft:empty") }))
                    if (!flagEncoding) add(entry(1, batch(nodes, index, translucent = true)))
                    add(entry(2, batch(nodes, index, translucent = false)))
                    add(entry(3, composite(nodes)))
                })
            }
        }
        val batched = original.deepCopy().apply { add("model", composite(children.filter { it !in alpha } + grouped)) }
        added += UVByteBuilder.of(item.path().removeSuffix(".json") + SUFFIX + ".json", item.estimatedSize(), batched)
        return resources + added
    }

    private fun composite(nodes: List<JsonObject>): JsonObject = JsonObject().apply {
        addProperty("type", "minecraft:composite")
        add("models", JsonArray().apply { nodes.forEach(::add) })
    }

    private fun entry(threshold: Int, model: JsonObject): JsonObject = JsonObject().apply {
        addProperty("threshold", threshold)
        add("model", model)
    }

    private fun json(resource: UVByteBuilder): JsonObject = JsonParser.parseString(resource.build().toString(Charsets.UTF_8)).asJsonObject
}
