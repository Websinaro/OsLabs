package com.oslab.simulator.security

/**
 * The exception boundary around a running simulation (Stage 7/8). Any
 * failure inside the virtual CPU/OS — including a deliberately hostile
 * update package or app — is caught here and turned into a contained
 * `Crashed` result, never an Android application crash. Callers get the
 * block's actual return value back on success, so this can wrap real work
 * (e.g. interpreter execution) and not just report pass/fail.
 */
object SandboxController {

    sealed class ContainedResult<out T> {
        data class Success<T>(val value: T) : ContainedResult<T>()
        data class Crashed(val reason: String) : ContainedResult<Nothing>()
    }

    fun <T> runContained(block: () -> T): ContainedResult<T> {
        return try {
            ContainedResult.Success(block())
        } catch (t: Throwable) {
            // Deliberately broad: an uploaded package's failure must never
            // propagate past this boundary and take the host app down.
            ContainedResult.Crashed(t.message ?: t::class.simpleName ?: "unknown error")
        }
    }
}
