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
import java.util.Collections

/** The same avatar armor materials, cut at the authored rig's waist, elbows and knees. */
internal object SegmentedArmorModels {
    internal data class Segment(
        val name: String,
        val width: Int,
        val height: Int,
        val depth: Int,
        val centerY: Float,
        val u: Int,
        val v: Int,
        val startY: Int,
        val totalHeight: Int
    ) {
        val internalTop get() = startY > 0
        val internalBottom get() = startY + height < totalHeight
    }

    private val namespace = UVNamespace("chronovale", "player_avatar")
    internal val slots = mapOf(
        "head" to listOf(Segment("head", 8, 8, 8, 4f, 0, 0, 0, 8)),
        "chest" to body() + limbs("arm"),
        "legs" to body() + limbs("leg"),
        "feet" to limbs("leg")
    )
    private data class Variant(val slot: String, val owner: Boolean, val opacity: Float)
    private val models = buildMap {
        for ((slot, parts) in slots) for (owner in listOf(false, true)) for (opacity in listOf(1f, VanillaArmorModels.FADED_OPACITY)) {
            put(Variant(slot, owner = owner, opacity), parts.associate { it.name to model(slot, it, owner = owner, opacity) })
        }
    }
    private data class Appearance(val slot: String, val asset: String, val dye: Int?, val pattern: String?, val material: String?)
    private val appearances = object : LinkedHashMap<Appearance, Map<String, UVModelData>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Appearance, Map<String, UVModelData>>?): Boolean = size > 256
    }

    @Synchronized
    fun clear() = appearances.clear()

    private fun body() = listOf("chest", "waist", "hip").mapIndexed { index, name ->
        Segment(name, 8, 4, 4, 2f, 16, 16, index * 4, 12)
    }

    private fun limbs(kind: String) = listOf("right", "left").flatMap { side ->
        val (u, v) = when {
            kind == "arm" && side == "right" -> 40 to 16
            kind == "arm" -> 32 to 48
            side == "right" -> 0 to 16
            else -> 16 to 48
        }
        listOf(
            Segment("${side}_$kind", 4, 6, 4, -3f, u, v, 0, 12),
            Segment("${side}_fore$kind", 4, 6, 4, -3f, u, v, 6, 12)
        )
    }

    internal fun model(slot: String, segment: Segment, owner: Boolean, opacity: Float): UVModel = with(segment) {
        val inflation = (if (slot == "legs") 0.5f else 1f) - if (name.endsWith("leg")) 0.1f else 0f
        val faces = linkedMapOf(
            UVFace.EAST to UVPos(u, v + depth + startY),
            UVFace.NORTH to UVPos(u + depth, v + depth + startY),
            UVFace.WEST to UVPos(u + depth + width, v + depth + startY),
            UVFace.SOUTH to UVPos(u + depth * 2 + width, v + depth + startY)
        )
        if (!internalTop) faces[UVFace.UP] = UVPos(u + depth, v)
        if (!internalBottom) faces[UVFace.DOWN] = UVPos(u + depth + width, v)
        val suffix = "${if (owner) "_owner" else ""}${if (opacity == VanillaArmorModels.FADED_OPACITY) "_faded" else ""}"
        val model = UVModel(namespace, "animation_armor_${slot}_$name$suffix").addElement(
            UVElement(
                ElementVector(width.toFloat(), height.toFloat(), depth.toFloat()).div(16f).inflate(inflation),
                ElementVector(0f, centerY, 0f).div(16f),
                UVSpace(width, height, depth), UVElement.ColorType.ARGB, faces
            )
        )
        fun cap(top: Boolean) = UVElement(
            ElementVector(width + 2f * inflation, 0f, depth + 2f * inflation).div(16f),
            ElementVector(0f, centerY + (height / 2f + inflation) * if (top) 1f else -1f, 0f).div(16f),
            UVSpace(1, 1, 1), UVElement.ColorType.ARGB,
            mapOf((if (top) UVFace.UP else UVFace.DOWN) to UVPos(if (top) 0 else 1, 0))
        )
        if (internalTop) model.addElement(cap(top = true))
        if (internalBottom) model.addElement(cap(top = false))
        model
    }

    fun write(block: (UVByteBuilder) -> Unit) {
        models.forEach { (variant, bones) ->
            bones.values.forEach { model ->
                BatchedSkinModels.export(model, VanillaArmorModels.texture(variant.owner, variant.opacity)).forEach {
                    block(it.withMetricUVs(forceTranslucent = variant.owner))
                }
            }
        }
    }

    fun items(slot: String, assetId: String, dyedColor: Int?, trimPattern: String?, trimMaterial: String?, cameraOwner: Boolean, opacity: Float): Map<String, TransformedItemStack>? {
        if (slot !in slots || opacity != 1f && opacity != VanillaArmorModels.FADED_OPACITY) return null
        val data = data(Appearance(slot, assetId, dyedColor, trimPattern, trimMaterial)) ?: return null
        val models = models.getValue(Variant(slot, owner = cameraOwner, opacity))
        return Collections.unmodifiableMap(data.mapValues { (bone, value) ->
            PLATFORM.nms().createSkinItem(BatchedSkinModels.itemModel(models.getValue(bone)), value.floats, value.flags, emptyList(), value.colors)
        })
    }

    @Synchronized
    private fun data(appearance: Appearance): Map<String, UVModelData>? {
        appearances[appearance]?.let { return it }
        val image = VanillaArmorModels.image(appearance.slot, appearance.asset, appearance.dye, appearance.pattern, appearance.material) ?: return null
        val models = models.getValue(Variant(appearance.slot, owner = false, 1f))
        return slots.getValue(appearance.slot).associate { part ->
            part.name to BatchedSkinModels.data(models.getValue(part.name).write(withJointCaps(image, part)))
        }.also { appearances[appearance] = it }
    }

    /** Internal faces have no vanilla texels. Average only covered edge pixels, leaving an empty segment empty. */
    internal fun withJointCaps(source: BufferedImage, segment: Segment): BufferedImage {
        if (!segment.internalTop && !segment.internalBottom) return source
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        result.setRGB(0, 0, source.width, source.height, source.getRGB(0, 0, source.width, source.height, null, 0, source.width), 0, source.width)
        fun cap(top: Boolean) {
            val y = segment.v + segment.depth + segment.startY + if (top) 0 else segment.height - 1
            var count = 0
            var red = 0
            var green = 0
            var blue = 0
            for (x in segment.u until segment.u + 2 * (segment.width + segment.depth)) {
                val color = source.getRGB(x, y)
                if (color ushr 24 == 0) continue
                count++
                red += color ushr 16 and 255
                green += color ushr 8 and 255
                blue += color and 255
            }
            val color = if (count == 0) 0 else 0xFF000000.toInt() or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            result.setRGB(if (top) 0 else 1, 0, color)
        }
        if (segment.internalTop) cap(top = true)
        if (segment.internalBottom) cap(top = false)
        return result
    }
}
