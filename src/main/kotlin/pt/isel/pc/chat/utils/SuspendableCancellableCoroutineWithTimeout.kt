package pt.isel.pc.chat.utils

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds


suspend inline fun <T> suspendCancellableCoroutineWithTimeout(
    timeout: Duration,
    crossinline block: (CancellableContinuation<T>) -> Unit
) = withTimeout(timeout.coerceAtLeast(1.nanoseconds)) {
    suspendCancellableCoroutine(block = block)
}