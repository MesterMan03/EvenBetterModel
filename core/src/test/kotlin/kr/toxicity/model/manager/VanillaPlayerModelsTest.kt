/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.toxicity.library.dynamicuv.UVByteBuilder
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VanillaPlayerModelsTest {

    @Test
    fun `rigid parts match vanilla bounds after native X and Y are flipped`() {
        // Values are the vanilla PlayerModel boxes, transformed independently of the generator.
        val classic = mapOf(
            "head" to Bounds(listOf(-4f, 0f, -4f), listOf(4f, 8f, 4f)),
            "body" to Bounds(listOf(-4f, -12f, -2f), listOf(4f, 0f, 2f)),
            "right_arm" to Bounds(listOf(-1f, -10f, -2f), listOf(3f, 2f, 2f)),
            "left_arm" to Bounds(listOf(-3f, -10f, -2f), listOf(1f, 2f, 2f)),
            "right_leg" to Bounds(listOf(-2f, -12f, -2f), listOf(2f, 0f, 2f)),
            "left_leg" to Bounds(listOf(-2f, -12f, -2f), listOf(2f, 0f, 2f))
        )
        for (slim in listOf(false, true)) {
            val expected = if (slim) classic + mapOf(
                "right_arm" to Bounds(listOf(-1f, -10f, -2f), listOf(2f, 2f, 2f)),
                "left_arm" to Bounds(listOf(-2f, -10f, -2f), listOf(1f, 2f, 2f))
            ) else classic
            val actual = VanillaPlayerModels.parts(slim = slim).associate { part ->
                part.name to bounds(part.model("_test", overlay = false).asJson())
            }
            assertEquals(expected, actual, "slim=$slim")
        }
    }

    @Test
    fun `overlay shells use native half pixel hat and quarter pixel clothing inflation`() {
        for (part in VanillaPlayerModels.parts(slim = false)) {
            val base = bounds(part.model("_test", overlay = false).asJson())
            val overlay = bounds(part.model("_test", overlay = true).asJson())
            val inflate = if (part.name == "head") 0.5f else 0.25f
            assertEquals(base.min.map { it - inflate }, overlay.min, part.name)
            assertEquals(base.max.map { it + inflate }, overlay.max, part.name)
        }
    }

    @Test
    fun `head texels face the correct way on every side including caps`() {
        val head = VanillaPlayerModels.parts(slim = false).first { it.name == "head" }.model("_test", overlay = false)
        val colors = head.write(coordinateSkin()).colors
        val geometry = json(head.asJson().first()).getAsJsonArray("elements")
        // DynamicUV's first texel in each face must agree with the transformed native cube UVs.
        val expected = mapOf(
            "north" to Pair(listOf(3f, 7f, -4f), rgb(8, 8)),
            "south" to Pair(listOf(-4f, 7f, 4f), rgb(24, 8)),
            "east" to Pair(listOf(4f, 7f, 3f), rgb(0, 8)),
            "west" to Pair(listOf(-4f, 7f, -4f), rgb(16, 8)),
            "up" to Pair(listOf(3f, 8f, 3f), rgb(8, 0)),
            "down" to Pair(listOf(3f, 0f, 3f), rgb(16, 0))
        )
        val firstByFace = geometry.map { it.asJsonObject }.groupBy { it.getAsJsonObject("faces").keySet().single() }
        assertEquals(expected.keys, firstByFace.keys)
        for ((face, value) in expected) {
            val element = firstByFace.getValue(face).first()
            val tint = element.getAsJsonObject("faces").getAsJsonObject(face).get("tintindex").asInt
            assertEquals(value.first, element.getAsJsonArray("from").map { it.asFloat - 8f }, face)
            assertEquals(value.second, colors.getInt(tint), face)
        }
    }

    @Test
    fun `slim sleeves use three pixel face widths and independent left arm skin regions`() {
        val expectedFront = mapOf("right_arm" to Pair(44, 20), "left_arm" to Pair(36, 52))
        val expectedOverlay = mapOf("right_arm" to Pair(44, 36), "left_arm" to Pair(52, 52))
        for (part in VanillaPlayerModels.parts(slim = true).filter { it.name.endsWith("_arm") }) {
            val data = part.model("_test", overlay = true).write(coordinateSkin())
            // Each arm has 2*(3*12 + 4*12 + 3*4) = 192 texels per skin layer.
            assertEquals(384, data.colors.size)
            assertEquals(192, data.floats.size)
            val base = expectedFront.getValue(part.name)
            val overlay = expectedOverlay.getValue(part.name)
            assertEquals(rgb(base.first, base.second), data.colors.getInt(0))
            assertEquals(rgb(base.first + 2, base.second + 11), data.colors.getInt(35))
            assertEquals(rgb(overlay.first, overlay.second), data.colors.getInt(192))
            assertEquals(rgb(overlay.first + 2, overlay.second + 11), data.colors.getInt(227))
        }
    }

    @Test
    fun `customization bits select native hat jacket sleeves and trousers independently`() {
        val bits = VanillaPlayerModels.parts(slim = false).associate { it.name to it.layerBit }
        assertEquals(
            mapOf("head" to 0x40, "body" to 0x02, "right_arm" to 0x08, "left_arm" to 0x04, "right_leg" to 0x20, "left_leg" to 0x10),
            bits
        )
    }

    @Test
    fun `export includes every audience variant and exact opaque and translucent shader markers`() {
        val resources = mutableListOf<UVByteBuilder>()
        VanillaPlayerModels.write(resources::add)
        assertEquals(resources.size, resources.map { it.path() }.toSet().size, "Duplicate pack paths")
        assertEquals(112, resources.count { it.path().contains("/items/") })
        val textures = resources.filter { it.path().endsWith(".png") }.associateBy { it.path().substringAfterLast('/') }
        val expected = mapOf(
            "observer_pixel.png" to 0xFFFCF9FB.toInt(), "observer_translucent_pixel.png" to 0x80FCF9FB.toInt(),
            "owner_pixel.png" to 0xFFFCF9FD.toInt(), "owner_translucent_pixel.png" to 0x80FCF9FD.toInt(),
            "hand_pixel.png" to 0xFFFCF9FC.toInt(), "hand_translucent_pixel.png" to 0x80FCF9FC.toInt()
        )
        assertEquals(expected.keys, textures.keys)
        for ((name, color) in expected) {
            val image = ImageIO.read(textures.getValue(name).build().inputStream())
            assertEquals(16, image.width)
            assertEquals(16, image.height)
            for (y in 0 until 16) for (x in 0 until 16) assertEquals(color, image.getRGB(x, y), "$name [$x,$y]")
        }
        for ((suffix, texture) in mapOf("base" to "observer", "base_owner" to "owner", "base_hand" to "hand")) {
            val model = resources.single { it.path().endsWith("/right_arm_classic_${suffix}_0.json") }
            assertEquals("chronovale:item/player_avatar/${texture}_pixel", json(model).getAsJsonObject("textures").get("0").asString)
        }
    }

    @Test
    fun `exported UV texel metrics match face geometry for base and inflated skin layers`() {
        val resources = mutableListOf<UVByteBuilder>()
        VanillaPlayerModels.write(resources::add)
        // Every audience/arm-width variant includes one composite base model and per-texel outer models.
        val models = resources.filter {
            it.path().contains("/models/") && (it.path().endsWith("_0.json") || it.path().endsWith("_1.json"))
        }
        assertEquals(84, models.size)
        for (resource in models) {
            for (entry in json(resource).getAsJsonArray("elements")) {
                val element = entry.asJsonObject
                val from = element.getAsJsonArray("from")
                val to = element.getAsJsonArray("to")
                val size = (0..2).map { to[it].asFloat - from[it].asFloat }
                for ((direction, face) in element.getAsJsonObject("faces").entrySet()) {
                    val uv = face.asJsonObject.getAsJsonArray("uv").map { it.asFloat }
                    val expected = when (direction) {
                        "east", "west" -> listOf(size[2], size[1])
                        "up", "down" -> listOf(size[0], size[2])
                        else -> listOf(size[0], size[1])
                    }
                    assertEquals(listOf(4f, 4f), uv.take(2), resource.path())
                    assertEquals(expected[0], uv[2] - uv[0], 0.00001f, resource.path())
                    assertEquals(expected[1], uv[3] - uv[1], 0.00001f, resource.path())
                    assertTrue(uv.all { it in 4f..12f }, resource.path())
                }
            }
        }
    }

    private data class Bounds(val min: List<Float>, val max: List<Float>)

    private fun bounds(resources: List<UVByteBuilder>): Bounds {
        val elements = resources.filter { it.path().contains("/models/") }.flatMap { json(it).getAsJsonArray("elements").map { element -> element.asJsonObject } }
        assertTrue(elements.isNotEmpty())
        return Bounds(
            (0..2).map { axis -> elements.minOf { it.getAsJsonArray("from")[axis].asFloat } - 8f },
            (0..2).map { axis -> elements.maxOf { it.getAsJsonArray("to")[axis].asFloat } - 8f }
        )
    }

    private fun json(resource: UVByteBuilder): JsonObject = JsonParser.parseString(resource.build().toString(Charsets.UTF_8)).asJsonObject

    private fun coordinateSkin() = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until width) setRGB(x, y, 0xFF000000.toInt() or rgb(x, y))
    }

    private fun rgb(x: Int, y: Int) = (x shl 16) or (y shl 8) or 0x37
}
