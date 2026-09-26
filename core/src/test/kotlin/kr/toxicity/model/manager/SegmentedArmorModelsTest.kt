/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.JsonParser
import kr.toxicity.library.dynamicuv.UVByteBuilder
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentedArmorModelsTest {
    @Test
    fun `armor retains every authored torso elbow and knee joint`() {
        assertEquals(
            mapOf(
                "head" to setOf("head"),
                "chest" to setOf("chest", "waist", "hip", "right_arm", "right_forearm", "left_arm", "left_forearm"),
                "legs" to setOf("chest", "waist", "hip", "right_leg", "right_foreleg", "left_leg", "left_foreleg"),
                "feet" to setOf("right_leg", "right_foreleg", "left_leg", "left_foreleg")
            ),
            SegmentedArmorModels.slots.mapValues { it.value.map { it.name }.toSet() }
        )
        for ((slot, segments) in SegmentedArmorModels.slots) for (segment in segments) {
            val model = SegmentedArmorModels.model(slot, segment, owner = false, opacity = 1f)
            val elements = model.asJson().filter { it.path().contains("/models/") }.flatMap {
                JsonParser.parseString(it.build().toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("elements").map { it.asJsonObject }
            }
            val inflation = (if (slot == "legs") 0.5f else 1f) - if (segment.name.endsWith("leg")) 0.1f else 0f
            val minimum = listOf(-segment.width / 2f - inflation, segment.centerY - segment.height / 2f - inflation, -segment.depth / 2f - inflation)
            val maximum = listOf(segment.width / 2f + inflation, segment.centerY + segment.height / 2f + inflation, segment.depth / 2f + inflation)
            for (axis in 0..2) {
                assertEquals(minimum[axis], elements.minOf { it.getAsJsonArray("from")[axis].asFloat } - 8f, 0.00001f, "$slot/${segment.name}")
                assertEquals(maximum[axis], elements.maxOf { it.getAsJsonArray("to")[axis].asFloat } - 8f, 0.00001f, "$slot/${segment.name}")
            }
        }
    }

    @Test
    fun `segments consume consecutive native side rows rather than stretching the whole armor over each bone`() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until 64) for (x in 0 until 64) setRGB(x, y, 0xFF000000.toInt() or (x shl 16) or (y shl 8) or 0x37)
        }
        val expected = mapOf("chest" to (20 to 20), "waist" to (20 to 24), "hip" to (20 to 28), "right_arm" to (44 to 20), "right_forearm" to (44 to 26), "left_arm" to (36 to 52), "left_forearm" to (36 to 58))
        for (segment in SegmentedArmorModels.slots.getValue("chest")) {
            val data = SegmentedArmorModels.model("chest", segment, owner = false, opacity = 1f).write(image)
            val coordinate = expected.getValue(segment.name)
            assertEquals(image.getRGB(coordinate.first, coordinate.second) and 0xFFFFFF, data.colors.getInt(0), segment.name)
            assertEquals(image.getRGB(coordinate.first + segment.width - 1, coordinate.second + segment.height - 1) and 0xFFFFFF, data.colors.getInt(segment.width * segment.height - 1), segment.name)
        }
    }

    @Test
    fun `internal joint caps use covered edge colors without changing the original atlas or filling empty armor segments`() {
        val segment = SegmentedArmorModels.slots.getValue("chest").first { it.name == "right_forearm" }
        val source = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply { setRGB(44, 26, 0xFF224466.toInt()) }
        val closed = SegmentedArmorModels.withJointCaps(source, segment)
        assertEquals(0xFF224466.toInt(), closed.getRGB(0, 0))
        assertEquals(0, source.getRGB(0, 0))
        assertEquals(source.getRGB(44, 26), closed.getRGB(44, 26))
        val empty = SegmentedArmorModels.withJointCaps(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), segment)
        assertEquals(0, empty.getRGB(0, 0))
        assertTrue(SegmentedArmorModels.model("chest", segment, owner = false, opacity = 1f).write(empty).flags.none { it })
    }

    @Test
    fun `animation export supplies both audiences and opacities with metric UVs including wide joint caps`() {
        val resources = mutableListOf<UVByteBuilder>()
        SegmentedArmorModels.write(resources::add)
        assertEquals(152, resources.count { it.path().contains("/items/") })
        assertEquals(resources.size, resources.map { it.path() }.toSet().size)
        var widest = 0f
        for (resource in resources.filter { it.path().contains("/models/") }) {
            val model = JsonParser.parseString(resource.build().toString(Charsets.UTF_8)).asJsonObject
            val owner = resource.path().contains("_owner")
            val faded = resource.path().contains("_faded")
            val texture = if (faded) "armor_${if (owner) "owner" else "observer"}_faded" else "${if (owner) "owner" else "observer"}_pixel"
            val material = model.getAsJsonObject("textures").get("0")
            assertEquals(owner, material.isJsonObject, resource.path())
            if (owner) assertTrue(material.asJsonObject.get("force_translucent").asBoolean, resource.path())
            assertEquals("chronovale:item/player_avatar/$texture", if (material.isJsonObject) material.asJsonObject.get("sprite").asString else material.asString)
            for (element in model.getAsJsonArray("elements")) for (face in element.asJsonObject.getAsJsonObject("faces").entrySet()) {
                val uv = face.value.asJsonObject.getAsJsonArray("uv").map { it.asFloat }
                assertTrue(uv.all { it in 4f..14f }, resource.path())
                widest = maxOf(widest, uv[2] - uv[0], uv[3] - uv[1])
            }
        }
        assertEquals(10f, widest)
    }

    @Test
    fun `unsupported opacity and slots fail before any runtime platform access`() {
        assertNull(SegmentedArmorModels.items("head", "minecraft:iron", null, null, null, cameraOwner = false, opacity = 0f))
        assertNull(SegmentedArmorModels.items("head", "minecraft:iron", null, null, null, cameraOwner = false, opacity = Float.NaN))
        assertNull(SegmentedArmorModels.items("body", "minecraft:iron", null, null, null, cameraOwner = false, opacity = 1f))
    }
}
