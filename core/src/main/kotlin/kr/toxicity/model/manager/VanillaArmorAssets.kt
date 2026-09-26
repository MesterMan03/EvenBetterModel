/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.image.BufferedImage
import java.io.File
import java.util.jar.JarFile
import javax.imageio.ImageIO

/** CPU copies of vanilla equipment layers; unsupported pack/registry extensions deliberately fail closed. */
internal class VanillaArmorAssets private constructor(
    private val equipment: Map<String, Equipment>,
    private val trims: Map<Pair<String, String>, BufferedImage>,
    private val palettes: Map<String, Map<Int, Int>>
) {
    private data class Layer(val image: BufferedImage, val undyedColor: Int?)
    private data class Equipment(val layers: Map<String, List<Layer>>, val overrides: Map<String, String>)

    fun image(slot: String, assetId: String, dyedColor: Int?, trimPattern: String?, trimMaterial: String?): BufferedImage? {
        if (slot !in VanillaArmorModels.slots || dyedColor != null && dyedColor !in 0..0xFFFFFF) return null
        if ((trimPattern == null) != (trimMaterial == null)) return null
        val definition = equipment[assetId] ?: return null
        val type = if (slot == "legs") "humanoid_leggings" else "humanoid"
        val layers = definition.layers[type] ?: return null
        val result = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
        for (layer in layers) {
            val tint = layer.undyedColor?.let { dyedColor ?: it } ?: 0xFFFFFF
            for (y in 0 until 32) for (x in 0 until 64) {
                val color = layer.image.getRGB(x, y)
                if (color ushr 24 != 0) result.setRGB(x, y, tint(color, tint))
            }
        }
        if (trimPattern != null && trimMaterial != null) {
            if (trimMaterial !in materials) return null
            val trim = trims[trimPattern to type] ?: return null
            val paletteId = definition.overrides[trimMaterial] ?: "minecraft:trim/${trimMaterial.substringAfter(':')}"
            val palette = palettes[paletteId] ?: return null
            for (y in 0 until 32) for (x in 0 until 64) {
                val color = trim.getRGB(x, y)
                if (color ushr 24 == 0) continue
                val replacement = palette[color and 0xFFFFFF] ?: return null
                if (replacement ushr 24 != 0) result.setRGB(x, y, replacement)
            }
        }
        return result
    }

    companion object {
        const val MINECRAFT_VERSION = "26.3"
        internal val armorNames = setOf("chainmail", "copper", "diamond", "gold", "iron", "leather", "netherite", "turtle_scute")
        internal val patterns = setOf(
            "bolt", "coast", "dune", "eye", "flow", "host", "raiser", "rib", "sentry", "shaper", "silence", "snout", "spire", "tide", "vex", "ward", "wayfinder", "wild"
        )
        internal val materialNames = setOf("amethyst", "copper", "diamond", "emerald", "gold", "iron", "lapis", "netherite", "quartz", "redstone", "resin")
        private val materials = materialNames.map { "minecraft:$it" }.toSet()

        fun load(file: File): VanillaArmorAssets = JarFile(file).use { jar ->
            val version = jar.getJarEntry("version.json")?.let { entry ->
                jar.getInputStream(entry).bufferedReader().use { JsonParser.parseReader(it).asJsonObject.get("id").asString }
            }
            check(version == MINECRAFT_VERSION) { "Avatar armor requires the $MINECRAFT_VERSION client assets, found $version" }
            load { path -> jar.getJarEntry(path)?.let { entry -> jar.getInputStream(entry).use { it.readAllBytes() } } }
        }

        internal fun load(read: (String) -> ByteArray?): VanillaArmorAssets {
            fun json(path: String): JsonObject? = read(path)?.let { JsonParser.parseString(it.toString(Charsets.UTF_8)).asJsonObject }
            val images = mutableMapOf<String, BufferedImage?>()
            fun image(path: String): BufferedImage? = images.getOrPut(path) {
                read(path)?.inputStream()?.use(ImageIO::read)?.let(::rawPixels)?.takeIf { image ->
                    // Default equipment uses binary cutout coverage. Refuse fractional coverage rather
                    // than silently changing its layering or allowing glint to fill transparent holes.
                    (0 until image.height).all { y -> (0 until image.width).all { x ->
                        val alpha = image.getRGB(x, y) ushr 24
                        alpha == 0 || alpha == 255
                    } }
                }
            }
            fun armorImage(path: String) = image(path)?.takeIf { it.width == 64 && it.height == 32 }
            val equipment = buildMap {
                for (name in armorNames) {
                    val definition = json("assets/minecraft/equipment/$name.json") ?: continue
                    if (!setOf("layers", "trim_overrides").containsAll(definition.keySet())) continue
                    val layers = mutableMapOf<String, List<Layer>>()
                    var valid = true
                    for (type in listOf("humanoid", "humanoid_leggings")) {
                        val entries = definition.getAsJsonObject("layers")?.getAsJsonArray(type) ?: continue
                        val parsed = mutableListOf<Layer>()
                        for (entry in entries) {
                            val layer = entry.asJsonObject
                            val texture = layer.get("texture")?.asString
                            if (texture == null || !texture.startsWith("minecraft:") || !setOf("texture", "dyeable").containsAll(layer.keySet())) {
                                valid = false
                                break
                            }
                            val source = armorImage("assets/minecraft/textures/entity/equipment/$type/${texture.substringAfter(':')}.png")
                            if (source == null) {
                                valid = false
                                break
                            }
                            val dyeable = layer.getAsJsonObject("dyeable")
                            if (dyeable != null && (dyeable.keySet() != setOf("color_when_undyed"))) {
                                valid = false
                                break
                            }
                            parsed += Layer(source, dyeable?.get("color_when_undyed")?.asInt?.and(0xFFFFFF))
                        }
                        if (parsed.isNotEmpty()) layers[type] = parsed
                    }
                    val overrides = mutableMapOf<String, String>()
                    for (entry in definition.getAsJsonArray("trim_overrides") ?: emptyList()) {
                        val override = entry.asJsonObject
                        val condition = override.getAsJsonObject("when")
                        val material = condition?.get("material")?.asString
                        val palette = override.get("palette")?.asString
                        if (override.keySet() != setOf("when", "palette") || condition?.keySet() != setOf("material") ||
                            material !in materials || palette?.startsWith("minecraft:trim/") != true
                        ) {
                            valid = false
                            break
                        }
                        overrides[material!!] = palette
                    }
                    if (valid && layers.isNotEmpty()) put("minecraft:$name", Equipment(layers.toMap(), overrides.toMap()))
                }
            }
            val base = image("assets/minecraft/textures/palettes/trim_base.png")
            val palettes = buildMap {
                if (base != null) for (name in materialNames + setOf("copper_darker", "diamond_darker", "gold_darker", "iron_darker", "netherite_darker")) {
                    val target = image("assets/minecraft/textures/palettes/trim/$name.png") ?: continue
                    if (target.width != base.width || target.height != base.height) continue
                    put("minecraft:trim/$name", buildMap {
                        for (y in 0 until base.height) for (x in 0 until base.width) put(base.getRGB(x, y) and 0xFFFFFF, target.getRGB(x, y))
                    })
                }
            }
            val trims = buildMap {
                for (pattern in patterns) for (type in listOf("humanoid", "humanoid_leggings")) {
                    val path = "assets/minecraft/textures/trims/entity/$type/$pattern.png"
                    val metadata = json("$path.mcmeta")?.getAsJsonObject("palette") ?: continue
                    if (metadata.get("base_palette")?.asString !in setOf("trim_base", "minecraft:trim_base")) continue
                    val source = armorImage(path) ?: continue
                    put("minecraft:$pattern" to type, source)
                }
            }
            return VanillaArmorAssets(equipment, trims, palettes)
        }

        private fun tint(color: Int, tint: Int): Int {
            fun channel(shift: Int) = ((color ushr shift and 255) * (tint ushr shift and 255) / 255) shl shift
            return (color and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
        }

        /** ImageIO.getRGB gamma-converts grayscale PNGs; Minecraft's PNG loader keeps their raw bytes. */
        internal fun rawPixels(source: BufferedImage): BufferedImage {
            val model = source.colorModel
            check(model.componentSize.all { it == 8 } && model.numColorComponents in setOf(1, 3)) { "Unsupported armor PNG color depth" }
            return BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB).apply {
                for (y in 0 until height) for (x in 0 until width) {
                    val values = model.getComponents(source.raster.getDataElements(x, y, null), null, 0)
                    val red = values[0]
                    val green = if (model.numColorComponents == 1) red else values[1]
                    val blue = if (model.numColorComponents == 1) red else values[2]
                    val alpha = if (model.hasAlpha()) values[model.numColorComponents] else 255
                    setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
                }
            }
        }
    }
}
