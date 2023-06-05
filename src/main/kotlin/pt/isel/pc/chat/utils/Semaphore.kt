package pt.isel.pc.chat.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.jvm.Throws
import kotlin.time.Duration

class Semaphore(initialUnits: Int) {

    init {
        require(initialUnits > 0) { "Initial units must be higher than zero" }
    }

    private var units: Int = initialUnits
    private val mutex = Mutex()
    private val guard = ReentrantLock()
    private val requests = LinkedList<Request>()

    private class Request(var continuation: CancellableContinuation<Unit>? = null) {
        var isDone = false
    }

    fun release() = guard.withLock {
        if (requests.isEmpty()) {
            units += 1
            null
        } else {
            requests.removeFirst()
        }
    }?.let {
        it.isDone = true
        it.continuation?.resume(Unit)
    }

    @Throws(TimeoutException::class, CancellationException::class)
    suspend fun acquire(timeout: Duration) {
        //fast-path
        mutex.lock()
        if (requests.isEmpty() && units != 0) {
            units -= 1
            mutex.unlock()
            return
        }

        //suspend-path
        if (timeout.isZero) throw TimeoutException("timeout")

        val request = Request()

        return suspendCancellableCoroutineWithTimeout(timeout) { cont ->
            try {
                request.continuation = cont
                requests.add(request)
                mutex.unlock()
            } catch (e: CancellationException) {
                handleCancellation(e, request)?.let {
                    mutex.unlock()
                    cont.resumeWithException(it)
                }
            }
        }
    }

    private fun handleCancellation(cause: Throwable?, request: Request): Throwable? {
        guard.withLock {
            if (!request.isDone) {
                request.isDone = true
                requests.remove(request)
                return cause ?: Exception("-- unexpected call to handleCancellation --")
            }
            return null
        }
    }
}