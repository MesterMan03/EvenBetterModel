/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.JsonParser
import kr.toxicity.library.dynamicuv.UVByteBuilder
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.JarFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VanillaArmorModelsTest {
    @Test
    fun `armor deformation and slot bone selection match native player armor`() {
        assertEquals(mapOf("head" to 1, "chest" to 3, "legs" to 3, "feet" to 2), VanillaArmorModels.slots.mapValues { it.value.size })
        val parts = VanillaPlayerModels.parts(slim = false).associateBy { it.name }
        for ((slot, bones) in VanillaArmorModels.slots) for (bone in bones) {
            val part = parts.getValue(bone)
            val expectedInflation = when {
                slot == "legs" && bone.endsWith("_leg") -> 0.4f
                slot == "legs" -> 0.5f
                bone.endsWith("_leg") -> 0.9f
                else -> 1f
            }
            val model = VanillaArmorModels.model(slot, part, owner = false)
            val elements = model.asJson().filter { it.path().contains("/models/") }.flatMap {
                JsonParser.parseString(it.build().toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("elements").map { it.asJsonObject }
            }
            val center = listOf(part.x, part.y, 0f)
            val size = listOf(part.width, part.height, part.depth)
            for (axis in 0..2) {
                assertEquals(center[axis] - size[axis] / 2f - expectedInflation, elements.minOf { it.getAsJsonArray("from")[axis].asFloat } - 8f, 0.00001f, "$slot $bone axis $axis min")
                assertEquals(center[axis] + size[axis] / 2f + expectedInflation, elements.maxOf { it.getAsJsonArray("to")[axis].asFloat } - 8f, 0.00001f, "$slot $bone axis $axis max")
            }
        }
    }

    @Test
    fun `mirrored left armor uses reversed texels and opposite side faces`() {
        val atlas = image { x, y -> 0xFF000000.toInt() or (x shl 16) or (y shl 8) or 0x37 }
        val mirrored = VanillaArmorModels.mirroredImage(atlas)
        // Independent coordinates from the native 64x32 cube net, reflected through native X.
        val armFaces = listOf(
            intArrayOf(36, 52, 47, 20), // north
            intArrayOf(44, 52, 55, 20), // south
            intArrayOf(32, 52, 51, 20), // east now uses the old west
            intArrayOf(40, 52, 43, 20), // west now uses the old east
            intArrayOf(36, 48, 47, 16), // up
            intArrayOf(40, 48, 51, 16) // down
        )
        for ((targetX, targetY, sourceX, sourceY) in armFaces) {
            assertEquals(atlas.getRGB(sourceX, sourceY), mirrored.getRGB(targetX, targetY))
            assertEquals(atlas.getRGB(sourceX - 3, sourceY), mirrored.getRGB(targetX + 3, targetY))
        }
        assertEquals(atlas.getRGB(7, 20), mirrored.getRGB(20, 52))
        assertEquals(atlas.getRGB(11, 20), mirrored.getRGB(16, 52))
        assertEquals(atlas.getRGB(3, 20), mirrored.getRGB(24, 52))
        assertEquals(atlas.getRGB(0, 0), mirrored.getRGB(0, 0))
    }

    @Test
    fun `transparent armor texels omit geometry rather than exposing a glint surface`() {
        val head = VanillaPlayerModels.parts(slim = false).first()
        val model = VanillaArmorModels.model("head", head, owner = false)
        val pixels = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply { setRGB(8, 8, 0xFFABCDEF.toInt()) }
        val data = model.write(pixels)
        assertEquals(384, data.flags.size)
        assertEquals(1, data.flags.count { it })
        assertEquals(0, data.floats.size)
        val item = model.asJson().single { it.path().contains("/items/") }.build().toString(Charsets.UTF_8)
        val conditions = JsonParser.parseString(item).asJsonObject.getAsJsonObject("model").getAsJsonArray("models")
        assertEquals(384, conditions.size())
        for (condition in conditions) {
            assertEquals("minecraft:condition", condition.asJsonObject.get("type").asString)
            assertEquals("minecraft:empty", condition.asJsonObject.getAsJsonObject("on_false").get("type").asString)
        }
    }

    @Test
    fun `exported armor keeps avatar metric UVs and owner texture markers`() {
        val resources = mutableListOf<UVByteBuilder>()
        VanillaArmorModels.write(resources::add)
        assertEquals(72, resources.count { it.path().contains("/items/") })
        assertEquals(resources.size, resources.map { it.path() }.toSet().size)
        for (resource in resources.filter { it.path().contains("/models/") }) {
            val model = JsonParser.parseString(resource.build().toString(Charsets.UTF_8)).asJsonObject
            val expected = if (resource.path().contains("_owner_")) "owner" else "observer"
            val texture = if (resource.path().contains("_faded_")) "armor_${expected}_faded" else "${expected}_pixel"
            val material = model.getAsJsonObject("textures").get("0")
            assertEquals(expected == "owner", material.isJsonObject, resource.path())
            if (material.isJsonObject) assertTrue(material.asJsonObject.get("force_translucent").asBoolean, resource.path())
            assertEquals("chronovale:item/player_avatar/$texture", if (material.isJsonObject) material.asJsonObject.get("sprite").asString else material.asString)
            for (entry in model.getAsJsonArray("elements")) {
                val element = entry.asJsonObject
                val size = (0..2).map { element.getAsJsonArray("to")[it].asFloat - element.getAsJsonArray("from")[it].asFloat }
                for ((direction, face) in element.getAsJsonObject("faces").entrySet()) {
                    val uv = face.asJsonObject.getAsJsonArray("uv").map { it.asFloat }
                    val metric = when (direction) {
                        "east", "west" -> size[2] to size[1]
                        "up", "down" -> size[0] to size[2]
                        else -> size[0] to size[1]
                    }
                    assertEquals(metric.first, uv[2] - uv[0], 0.00001f)
                    assertEquals(metric.second, uv[3] - uv[1], 0.00001f)
                }
            }
        }
    }

    @Test
    fun `faded armor changes only material opacity while keeping cutouts tint data and geometry`() {
        val resources = mutableListOf<UVByteBuilder>()
        VanillaArmorModels.write(resources::add)
        val textures = resources.filter { it.path().endsWith(".png") }
        assertEquals(2, textures.size)
        for (texture in textures) {
            val image = ImageIO.read(texture.build().inputStream())
            val rgb = if (texture.path().contains("_owner_")) VanillaPlayerModels.OWNER_RGB else VanillaPlayerModels.OBSERVER_RGB
            assertEquals(16, image.width)
            assertEquals(16, image.height)
            for (y in 0 until 16) for (x in 0 until 16) {
                assertEquals((102 shl 24) or rgb, image.getRGB(x, y))
            }
        }
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply { setRGB(8, 8, 0xFF234567.toInt()) }
        val part = VanillaPlayerModels.parts(slim = false).first()
        for (owner in listOf(false, true)) {
            val opaque = VanillaArmorModels.model("head", part, owner = owner)
            val faded = VanillaArmorModels.model("head", part, owner = owner, opacity = 0.4f)
            val opaqueData = opaque.write(image)
            val fadedData = faded.write(image)
            assertEquals(opaqueData.colors, fadedData.colors)
            assertEquals(opaqueData.flags, fadedData.flags)
            assertEquals(1, fadedData.flags.count { it })
            fun geometry(model: kr.toxicity.library.dynamicuv.UVModel, opacity: Float) =
                model.asJson(VanillaArmorModels.texture(owner = owner, opacity)).filter { it.path().contains("/models/") }.map {
                    JsonParser.parseString(it.build().toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("elements")
                }
            assertEquals(geometry(opaque, 1f), geometry(faded, 0.4f))
        }
        for (opacity in listOf(0f, -1f, 0.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(VanillaArmorModels.items("head", "minecraft:iron", null, null, null, cameraOwner = false, opacity))
        }
    }

    @Test
    fun `leather uses dye only on its dyeable layer and retains undyed overlay`() {
        val assets = assets(leather = true)
        val dyed = assertNotNull(assets.image("chest", "minecraft:leather", 0x4080FF, null, null))
        assertEquals(0xFF2040FF.toInt(), dyed.getRGB(1, 0))
        assertEquals(0xFF987654.toInt(), dyed.getRGB(0, 0))
        val undyed = assertNotNull(assets.image("chest", "minecraft:leather", null, null, null))
        assertEquals(0xFF402020.toInt(), undyed.getRGB(1, 0))
    }

    @Test
    fun `trim composition preserves cutouts and applies matching material darker palette`() {
        val assets = assets(leather = false)
        val iron = assertNotNull(assets.image("legs", "minecraft:iron", null, "minecraft:coast", "minecraft:iron"))
        assertEquals(0xFF334455.toInt(), iron.getRGB(0, 0))
        assertEquals(0xFF8080FF.toInt(), iron.getRGB(1, 0))
        val gold = assertNotNull(assets.image("legs", "minecraft:iron", 0, "minecraft:coast", "minecraft:gold"))
        assertEquals(0xFFFFCC22.toInt(), gold.getRGB(0, 0))
        assertEquals(0xFF8080FF.toInt(), gold.getRGB(1, 0), "Non-dyeable iron ignores a dye component")
    }

    @Test
    fun `unsupported or incomplete equipment requests cannot silently lose appearance`() {
        val assets = assets(leather = false)
        assertNull(assets.image("head", "custom:iron", null, null, null))
        assertNull(assets.image("head", "minecraft:elytra", null, null, null))
        assertNull(assets.image("body", "minecraft:iron", null, null, null))
        assertNull(assets.image("head", "minecraft:iron", -1, null, null))
        assertNull(assets.image("head", "minecraft:iron", null, "minecraft:coast", null))
        assertNull(assets.image("head", "minecraft:iron", null, "custom:coast", "minecraft:iron"))
        assertNull(assets.image("head", "minecraft:iron", null, "minecraft:coast", "custom:iron"))
    }

    @Test
    fun `grayscale trim PNGs preserve raw palette bytes without ImageIO gamma conversion`() {
        val grayscale = BufferedImage(2, 1, BufferedImage.TYPE_BYTE_GRAY).apply {
            raster.setSample(0, 0, 0, 32)
            raster.setSample(1, 0, 0, 128)
        }
        val decoded = VanillaArmorAssets.rawPixels(grayscale)
        assertEquals(0xFF202020.toInt(), decoded.getRGB(0, 0))
        assertEquals(0xFF808080.toInt(), decoded.getRGB(1, 0))
    }

    @Test
    fun `available native client assets cover every default armor and trim combination`() {
        val path = System.getenv("BETTERMODEL_ARMOR_TEST_CLIENT")
        assumeTrue(path != null, "Set BETTERMODEL_ARMOR_TEST_CLIENT to validate a local vanilla client jar")
        val assets = VanillaArmorAssets.load(File(path!!))
        JarFile(path).use { jar ->
            for (material in VanillaArmorAssets.materialNames) {
                val entry = jar.getJarEntry("data/minecraft/trim_material/$material.json")
                val json = jar.getInputStream(entry).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
                assertEquals("minecraft:trim/$material", json.get("palette_id").asString)
            }
        }
        for (armor in VanillaArmorAssets.armorNames) for (slot in VanillaArmorModels.slots.keys) {
            // Turtle scute supplies a humanoid texture only, so leggings are correctly unsupported.
            if (armor == "turtle_scute" && slot == "legs") continue
            val untrimmed = assertNotNull(assets.image(slot, "minecraft:$armor", null, null, null), "$armor $slot")
            assertTrue((0 until 32).any { y -> (0 until 64).any { x -> untrimmed.getRGB(x, y) ushr 24 != 0 } })
            for (pattern in VanillaArmorAssets.patterns) for (material in VanillaArmorAssets.materialNames) {
                assertNotNull(assets.image(slot, "minecraft:$armor", null, "minecraft:$pattern", "minecraft:$material"), "$armor $slot $pattern $material")
            }
        }
    }

    private fun assets(leather: Boolean): VanillaArmorAssets {
        val files = mutableMapOf<String, ByteArray>()
        fun add(path: String, value: String) { files[path] = value.toByteArray() }
        fun addImage(path: String, image: BufferedImage) { files[path] = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray() }
        val name = if (leather) "leather" else "iron"
        val layer = if (leather) """{"texture":"minecraft:leather","dyeable":{"color_when_undyed":8405024}},{"texture":"minecraft:leather_overlay"}""" else """{"texture":"minecraft:iron"}"""
        add("assets/minecraft/equipment/$name.json", """{"layers":{"humanoid":[$layer],"humanoid_leggings":[$layer]},"trim_overrides":[{"when":{"material":"minecraft:iron"},"palette":"minecraft:trim/iron_darker"}]}""")
        for (type in listOf("humanoid", "humanoid_leggings")) {
            addImage("assets/minecraft/textures/entity/equipment/$type/$name.png", image { _, _ -> 0xFF8080FF.toInt() })
            if (leather) addImage("assets/minecraft/textures/entity/equipment/$type/leather_overlay.png", image { x, y -> if (x == 0 && y == 0) 0xFF987654.toInt() else 0 })
            addImage("assets/minecraft/textures/trims/entity/$type/coast.png", image { x, y -> if (x == 0 && y == 0) 0xFF112233.toInt() else 0 })
            add("assets/minecraft/textures/trims/entity/$type/coast.png.mcmeta", """{"palette":{"base_palette":"trim_base"}}""")
        }
        fun palette(color: Int) = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, color) }
        addImage("assets/minecraft/textures/palettes/trim_base.png", palette(0xFF112233.toInt()))
        addImage("assets/minecraft/textures/palettes/trim/iron_darker.png", palette(0xFF334455.toInt()))
        addImage("assets/minecraft/textures/palettes/trim/gold.png", palette(0xFFFFCC22.toInt()))
        return VanillaArmorAssets.load(files::get)
    }

    private fun image(pixel: (Int, Int) -> Int) = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until width) setRGB(x, y, pixel(x, y))
    }
}
