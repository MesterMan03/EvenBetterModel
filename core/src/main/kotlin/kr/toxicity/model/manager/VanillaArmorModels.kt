/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import kr.toxicity.library.dynamicuv.*
import kr.toxicity.model.api.util.TransformedItemStack
import kr.toxicity.model.manager.VanillaPlayerModels.withMetricUVs
import kr.toxicity.model.util.PLATFORM
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import kotlin.math.roundToInt

/** Rigid armor shares avatar bones and timing, while each equipment slot keeps its own glint state. */
internal object VanillaArmorModels {
    // Both rigid and segmented armor use this pack-baked opacity; keep ChronoCore's request in sync.
    const val FADED_OPACITY = 0.4f

    internal val slots = mapOf(
        "head" to setOf("head"),
        "chest" to setOf("body", "right_arm", "left_arm"),
        "legs" to setOf("body", "right_leg", "left_leg"),
        "feet" to setOf("right_leg", "left_leg")
    )
    private val namespace = UVNamespace("chronovale", "player_avatar")
    private val observerTexture = UVTextureName("observer_pixel", "observer_translucent_pixel")
    private val ownerTexture = UVTextureName("owner_pixel", "owner_translucent_pixel")
    private val fadedObserverTexture = UVTextureName("armor_observer_faded", "armor_observer_faded")
    private val fadedOwnerTexture = UVTextureName("armor_owner_faded", "armor_owner_faded")
    private val nativeParts = VanillaPlayerModels.parts(slim = false)
    private data class ModelKey(val slot: String, val owner: Boolean, val faded: Boolean = false)
    private val models = buildMap {
        for ((slot, bones) in slots) for (owner in listOf(false, true)) for (faded in listOf(false, true)) {
            put(ModelKey(slot, owner = owner, faded = faded), nativeParts.filter { it.name in bones }.associate { part ->
                part.name to model(slot, part, owner = owner, opacity = if (faded) FADED_OPACITY else 1f)
            })
        }
    }
    private data class Appearance(val slot: String, val asset: String, val dye: Int?, val pattern: String?, val material: String?)
    private var assets: VanillaArmorAssets? = null
    private val appearances = object : LinkedHashMap<Appearance, Map<String, UVModelData>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Appearance, Map<String, UVModelData>>?): Boolean = size > 256
    }

    fun reload(client: File?) {
        synchronized(this) {
            assets = null
            appearances.clear()
            if (client != null) assets = VanillaArmorAssets.load(client)
        }
        // Segmented lookups call image() below; never acquire their cache lock while holding ours.
        SegmentedArmorModels.clear()
    }

    internal fun model(slot: String, part: VanillaPlayerModels.Part, owner: Boolean, opacity: Float = 1f): UVModel {
        require(opacity == 1f || opacity == FADED_OPACITY) { "Unsupported armor opacity" }
        // Both classic/slim players use the same native armor geometry. All armor legs are
        // contracted by .1px in HumanoidModel.createBaseArmorMesh, after the slot's deformation.
        val inflation = (if (slot == "legs") 0.5f else 1f) - if (part.name.endsWith("_leg")) 0.1f else 0f
        return UVModel(namespace, "armor_${slot}_${part.name}${if (owner) "_owner" else ""}${if (opacity == FADED_OPACITY) "_faded" else ""}").addElement(
            UVElement(
                ElementVector(part.width.toFloat(), part.height.toFloat(), part.depth.toFloat()).div(16f).inflate(inflation),
                ElementVector(part.x, part.y, 0f).div(16f),
                UVSpace(part.width, part.height, part.depth),
                UVElement.ColorType.ARGB,
                faces(part)
            )
        )
    }

    fun write(block: (UVByteBuilder) -> Unit) {
        models.forEach { (key, bones) ->
            bones.values.forEach { model ->
                BatchedSkinModels.export(model, texture(owner = key.owner, opacity = if (key.faded) FADED_OPACITY else 1f)).forEach { block(it.withMetricUVs()) }
            }
        }
        for ((name, rgb) in listOf("armor_observer_faded" to VanillaPlayerModels.OBSERVER_RGB, "armor_owner_faded" to VanillaPlayerModels.OWNER_RGB)) {
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 16) for (x in 0 until 16) image.setRGB(x, y, ((FADED_OPACITY * 255).roundToInt() shl 24) or rgb)
            block(UVByteBuilder.of(namespace.texture(name), image))
        }
    }

    internal fun texture(owner: Boolean, opacity: Float): UVTextureName {
        require(opacity == 1f || opacity == FADED_OPACITY) { "Unsupported armor opacity" }
        return if (opacity == FADED_OPACITY) {
            if (owner) fadedOwnerTexture else fadedObserverTexture
        } else {
            if (owner) ownerTexture else observerTexture
        }
    }

    fun items(slot: String, assetId: String, dyedColor: Int?, trimPattern: String?, trimMaterial: String?, cameraOwner: Boolean, opacity: Float = 1f): Map<String, TransformedItemStack>? {
        if (opacity != 1f && opacity != FADED_OPACITY) return null
        val appearance = Appearance(slot, assetId, dyedColor, trimPattern, trimMaterial)
        val data = data(appearance) ?: return null
        val models = models.getValue(ModelKey(slot, owner = cameraOwner, faded = opacity == FADED_OPACITY))
        return Collections.unmodifiableMap(data.mapValues { (bone, value) ->
            PLATFORM.nms().createSkinItem(BatchedSkinModels.itemModel(models.getValue(bone)), value.floats, value.flags, emptyList(), value.colors)
        })
    }

    @Synchronized
    private fun data(appearance: Appearance): Map<String, UVModelData>? {
        appearances[appearance]?.let { return it }
        val image = image(appearance.slot, appearance.asset, appearance.dye, appearance.pattern, appearance.material) ?: return null
        return models.getValue(ModelKey(appearance.slot, owner = false)).mapValues { BatchedSkinModels.data(it.value.write(image)) }.also { appearances[appearance] = it }
    }

    @Synchronized
    internal fun image(slot: String, assetId: String, dyedColor: Int?, trimPattern: String?, trimMaterial: String?): BufferedImage? =
        assets?.image(slot, assetId, dyedColor, trimPattern, trimMaterial)?.let(::mirroredImage)

    /** Convert the native mirrored left cubes into independent UV regions before encoding tint data. */
    internal fun mirroredImage(armor: BufferedImage): BufferedImage {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 32) for (x in 0 until 64) image.setRGB(x, y, armor.getRGB(x, y))
        for (suffix in listOf("arm", "leg")) {
            val right = nativeParts.first { it.name == "right_$suffix" }
            val left = nativeParts.first { it.name == "left_$suffix" }
            val source = faces(right)
            for ((face, target) in faces(left)) {
                val mirroredFace = when (face) {
                    UVFace.EAST -> UVFace.WEST
                    UVFace.WEST -> UVFace.EAST
                    else -> face
                }
                val start = source.getValue(mirroredFace)
                val (width, height) = when (face) {
                    UVFace.EAST, UVFace.WEST -> right.depth to right.height
                    UVFace.UP, UVFace.DOWN -> right.width to right.depth
                    else -> right.width to right.height
                }
                for (y in 0 until height) for (x in 0 until width) {
                    image.setRGB(target.x() + x, target.z() + y, armor.getRGB(start.x() + width - 1 - x, start.z() + y))
                }
            }
        }
        return image
    }

    private fun faces(part: VanillaPlayerModels.Part): Map<UVFace, UVPos> = with(part) {
        mapOf(
            UVFace.EAST to UVPos(baseU, baseV + depth),
            UVFace.NORTH to UVPos(baseU + depth, baseV + depth),
            UVFace.WEST to UVPos(baseU + depth + width, baseV + depth),
            UVFace.SOUTH to UVPos(baseU + depth * 2 + width, baseV + depth),
            UVFace.UP to UVPos(baseU + depth, baseV),
            UVFace.DOWN to UVPos(baseU + depth + width, baseV)
        )
    }
}
