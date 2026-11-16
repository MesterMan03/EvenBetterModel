/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit

import kr.toxicity.model.api.BetterModelPlatform.ReloadResult
import kr.toxicity.model.api.pack.PackMeta
import kr.toxicity.model.api.pack.PackResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitialModelLoadTest {
    private fun success() = ReloadResult.Success(
        true, 0,
        PackResult(
            PackMeta(PackMeta.Pack(1, "test", PackMeta.VersionRange(1), PackMeta.PackVersion(1), PackMeta.PackVersion(1)), null),
            null
        )
    )

    @Test
    fun `startup racing an existing reload waits for its real completion`() {
        val successes = mutableListOf<ReloadResult.Success>()
        val failures = mutableListOf<Throwable>()
        val initialLoad = InitialModelLoad(successes::add, failures::add)
        val reload = CompletableFuture<ReloadResult>()
        reload.thenAccept(initialLoad::complete)

        initialLoad.complete(ReloadResult.OnReload.INSTANCE)
        assertFalse(initialLoad.isComplete)
        assertTrue(successes.isEmpty())
        assertTrue(failures.isEmpty())

        val result = success()
        reload.complete(result)
        assertTrue(initialLoad.isComplete)
        assertEquals(listOf(result), successes)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a competing reload failure stays visible and a later successful retry finishes startup`() {
        var successes = 0
        val failures = mutableListOf<Throwable>()
        val initialLoad = InitialModelLoad({ successes++ }, failures::add)
        val failure = IllegalStateException("Invalid model")

        initialLoad.complete(ReloadResult.OnReload.INSTANCE)
        initialLoad.complete(ReloadResult.Failure(failure))
        assertFalse(initialLoad.isComplete)
        assertEquals(listOf<Throwable>(failure), failures)

        initialLoad.complete(success())
        initialLoad.complete(success())
        initialLoad.complete(ReloadResult.Failure(IllegalStateException("Later administrative reload")))
        assertTrue(initialLoad.isComplete)
        assertEquals(1, successes)
        assertEquals(listOf<Throwable>(failure), failures)
    }

    @Test
    fun `concurrent completion reports startup success only once`() {
        val successes = java.util.concurrent.atomic.AtomicInteger()
        val initialLoad = InitialModelLoad({ successes.incrementAndGet() }, { throw AssertionError("Unexpected failure", it) })
        val result = success()
        Executors.newFixedThreadPool(2).use { executor ->
            val tasks = (1..20).map { executor.submit { initialLoad.complete(result) } }
            tasks.forEach { it.get() }
        }
        assertTrue(initialLoad.isComplete)
        assertEquals(1, successes.get())
    }
}
