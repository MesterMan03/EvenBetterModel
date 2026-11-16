/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit

import kr.toxicity.model.api.BetterModelPlatform.ReloadResult
import java.util.concurrent.atomic.AtomicBoolean

/** Startup may share a reload already started by another plugin; only its completed result establishes readiness. */
internal class InitialModelLoad(
    private val onSuccess: (ReloadResult.Success) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val completed = AtomicBoolean()
    val isComplete: Boolean get() = completed.get()

    fun complete(result: ReloadResult) {
        when (result) {
            is ReloadResult.Success -> if (completed.compareAndSet(false, true)) onSuccess(result)
            is ReloadResult.Failure -> if (!completed.get()) onFailure(result.throwable())
            is ReloadResult.OnReload -> Unit
        }
    }
}
