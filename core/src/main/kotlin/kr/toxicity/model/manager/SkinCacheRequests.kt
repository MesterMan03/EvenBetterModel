/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import java.util.UUID

/** Serializes per-profile cache invalidation with publication by asynchronous skin requests. */
internal class SkinCacheRequests {
    private val current = mutableMapOf<UUID, Any>()

    @Synchronized
    fun begin(key: UUID): Any = Any().also { current[key] = it }

    @Synchronized
    fun <T> ifCurrent(key: UUID, request: Any, action: () -> T): T? =
        if (current[key] === request) action() else null

    @Synchronized
    fun invalidate(key: UUID, action: () -> Unit) {
        current.remove(key)
        action()
    }

    @Synchronized
    fun release(key: UUID, request: Any) {
        current.remove(key, request)
    }
}
