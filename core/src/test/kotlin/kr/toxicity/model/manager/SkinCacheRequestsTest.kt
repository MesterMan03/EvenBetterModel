/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkinCacheRequestsTest {

    @Test
    fun `old download cannot replace a skin fetched after invalidation`() {
        val requests = SkinCacheRequests()
        val key = UUID.randomUUID()
        val cache = mutableMapOf<UUID, String>()
        val oldDownload = CompletableFuture<String>()
        val oldRequest = requests.begin(key)
        val oldCompletion = oldDownload.thenAccept { skin ->
            requests.ifCurrent(key, oldRequest) { cache[key] = skin }
        }

        requests.invalidate(key) { cache.remove(key) }
        val newRequest = requests.begin(key)
        requests.ifCurrent(key, newRequest) { cache[key] = "new skin" }
        oldDownload.complete("old skin")
        oldCompletion.join()

        assertEquals("new skin", cache[key])
        requests.ifCurrent(key, oldRequest) { cache.remove(key) }
        requests.release(key, oldRequest)
        assertEquals("new skin", cache[key], "An old failure or expiration cannot invalidate a newer skin")
        assertEquals(true, requests.ifCurrent(key, newRequest) { true })
    }

    @Test
    fun `invalidation rejects an in-flight download without another request`() {
        val requests = SkinCacheRequests()
        val key = UUID.randomUUID()
        val request = requests.begin(key)
        var published = false

        requests.invalidate(key) {}
        requests.ifCurrent(key, request) { published = true }

        assertFalse(published)
    }

    @Test
    fun `requests for different players do not invalidate each other and released tokens stay stale`() {
        val requests = SkinCacheRequests()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val firstRequest = requests.begin(first)
        val secondRequest = requests.begin(second)

        requests.release(first, firstRequest)
        val replacement = requests.begin(first)
        assertEquals(null, requests.ifCurrent(first, firstRequest) { true })
        assertEquals(true, requests.ifCurrent(first, replacement) { true })
        assertEquals(true, requests.ifCurrent(second, secondRequest) { true })
    }

    @Test
    fun `invalidation cannot fall between the token check and publication`() {
        val requests = SkinCacheRequests()
        val key = UUID.randomUUID()
        val request = requests.begin(key)
        val publishing = CountDownLatch(1)
        val invalidating = CountDownLatch(1)
        val allowPublication = CountDownLatch(1)
        var cached: String? = null
        val publication = CompletableFuture.runAsync {
            requests.ifCurrent(key, request) {
                publishing.countDown()
                check(allowPublication.await(5, TimeUnit.SECONDS))
                cached = "skin"
            }
        }
        try {
            assertTrue(publishing.await(5, TimeUnit.SECONDS))
            val invalidation = CompletableFuture.runAsync {
                invalidating.countDown()
                requests.invalidate(key) { cached = null }
            }
            assertTrue(invalidating.await(5, TimeUnit.SECONDS))
            allowPublication.countDown()
            publication.get(5, TimeUnit.SECONDS)
            invalidation.get(5, TimeUnit.SECONDS)
            assertEquals(null, cached)
        } finally {
            allowPublication.countDown()
        }
    }
}
