/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.BetterModelConfig
import kr.toxicity.model.api.BetterModelPlatform
import kr.toxicity.model.api.config.ModuleConfig
import kr.toxicity.model.api.config.PackConfig
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class LimbPackResourcesTest {
    @Test
    fun `production skin pack has valid unique paths and resolvable models with obfuscation on and off`() {
        val field = BetterModel::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = field.get(null)
        try {
            for (obfuscated in listOf(false, true)) {
                val config = stub<BetterModelConfig>(mapOf(
                    "namespace" to "bettermodel",
                    "pack" to PackConfig(obfuscated),
                    "module" to ModuleConfig(true, true, false)
                ))
                BetterModel.register(stub<BetterModelPlatform>(mapOf("config" to config)))
                val resources = linkedMapOf<String, ByteArray>()
                SkinManagerImpl.write { resource ->
                    val path = resource.path()
                    assertTrue(path.matches(Regex("assets/[a-z0-9_.-]+/(models|items|textures)/[a-z0-9/._-]+\\.(json|png)")), path)
                    val bytes = resource.build()
                    resources.putIfAbsent(path, bytes)?.let { existing ->
                        assertContentEquals(existing, bytes, "Different resources collided at $path")
                    }
                }
                assertTrue(resources.keys.any { "/items/player_limb/" in it }, "Missing sitting-NPC limb definitions")
                assertTrue(resources.keys.any { "/items/player_avatar/" in it }, "Missing avatar definitions")
                var references = 0
                fun visit(element: JsonElement) {
                    if (element.isJsonArray) {
                        element.asJsonArray.forEach(::visit)
                    } else if (element.isJsonObject) {
                        for ((key, value) in element.asJsonObject.entrySet()) {
                            if (key == "model" && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                                val id = value.asString
                                assertTrue(id.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")), id)
                                val path = "assets/${id.substringBefore(':')}/models/${id.substringAfter(':')}.json"
                                assertTrue(path in resources, "Missing geometry $id")
                                references++
                            } else {
                                visit(value)
                            }
                        }
                    }
                }
                resources.filterKeys { it.endsWith(".json") }.values.forEach {
                    visit(JsonParser.parseString(it.toString(Charsets.UTF_8)))
                }
                assertTrue(references > 100, "Expected full limb and avatar geometry")
                println("Obfuscated=$obfuscated: validated ${resources.size} resources and $references model references")
            }
        } finally {
            field.set(null, previous)
        }
    }

    private inline fun <reified T> stub(values: Map<String, Any>): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { _, method, _ -> values[method.name] ?: error("Unexpected platform call: ${method.name}") } as T
}
