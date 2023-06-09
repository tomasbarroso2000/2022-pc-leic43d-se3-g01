package pt.isel.pc.chat.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.jvm.Throws
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class Semaphore(initialUnits: Int) {
    private var units: Int = initialUnits
    private val guard = ReentrantLock()
    private val requests = LinkedList<Request>()

    private class Request(var continuation: CancellableContinuation<Unit>? = null) {
        var isDone = false
    }

    fun release() = guard.withLock {
        if (requests.isEmpty()) {
            units += 1
            null
        } else requests.removeFirst()
    }?.let {
        it.isDone = true
        it.continuation?.resume(Unit)
    }

    @Throws(TimeoutException::class, CancellationException::class)
    suspend fun acquire(timeout: Duration) {
        //fast-path
        val request = Request()

        try {
            withTimeout(timeout.coerceAtLeast(1.nanoseconds)) {
                suspendCancellableCoroutine<Unit> { cont ->
                    guard.withLock {
                        if (requests.isEmpty() && units != 0) {
                            units -= 1
                            cont.resume(Unit)
                        } else {
                            // suspend path
                            request.continuation = cont
                            requests.add(request)
                        }
                    }
                }
            }
        }
        catch (e : CancellationException) {
            handleCancellation(e, request)
        }
    }


    private fun handleCancellation(cause: Throwable?, request: Request) {
        guard.withLock {
            if (!request.isDone) {
                request.isDone = true
                requests.remove(request)
                throw cause ?: Exception("-- unexpected call to handleCancellation --")
            }
        }
    }
}