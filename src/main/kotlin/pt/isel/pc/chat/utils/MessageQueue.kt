package pt.isel.pc.chat.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.jvm.Throws
import kotlin.time.Duration


class MessageQueue<T>(private val capacity: Int) {

    init {
        require(capacity > 0) { "Capacity must be greater than zero" }
    }

    private sealed class Waiter<T>(var continuation: CancellableContinuation<T>?) {
        var done: Boolean = false
    }

    private class EnqueueWaiter<T>(continuation: CancellableContinuation<Unit>? = null, val message: T) :
        Waiter<Unit>(continuation)

    private class DequeueWaiter<T>(continuation: CancellableContinuation<T>? = null) : Waiter<T>(continuation)

    private val mutex = ReentrantLock()
    private val messages = LinkedList<T>()
    private val enqueues = LinkedList<EnqueueWaiter<T>>()
    private val dequeues = LinkedList<DequeueWaiter<T>>()

    @Throws(TimeoutException::class, CancellationException::class)
    suspend fun dequeue(timeout: Duration): T {
        //fast-path
        mutex.lock()
        if (messages.isNotEmpty() && dequeues.isEmpty()) {
            val message = messages.removeFirst()
            computeEnqueues()
            mutex.unlock()
            return message
        }
        //suspend-path
        if (timeout.isZero) throw TimeoutException("timeout")

        val waiter = DequeueWaiter<T>()

        return suspendCancellableCoroutineWithTimeout(timeout) { cont ->
            try {
                waiter.continuation = cont
                dequeues.add(waiter)
                mutex.unlock()
            } catch (e: CancellationException) {
                handleCancellation(e, waiter)?.let {
                    mutex.unlock()
                    cont.resumeWithException(it)
                }
            }
        }
    }

    @Throws(CancellationException::class)
    suspend fun enqueue(message: T) {
        // fast path
        mutex.lock()
        if (enqueues.isEmpty() && messages.size < capacity) {
            messages.add(message)
            computeDequeues()
            mutex.unlock()
            return
        }
        //suspend-path
        val waiter = EnqueueWaiter(message = message)

        return suspendCancellableCoroutine { cont ->
            try {
                waiter.continuation = cont
                enqueues.add(waiter)
                mutex.unlock()
            } catch (e: CancellationException) {
                handleCancellation(e, waiter)?.let {
                    mutex.unlock()
                    cont.resumeWithException(it)
                }
            }
        }
    }

    private fun handleCancellation(cause: Throwable?, waiter: Waiter<*>): Throwable? {
        mutex.withLock {
            if (!waiter.done) {
                waiter.done = true
                when (waiter) {
                    is EnqueueWaiter<*> -> enqueues.remove(waiter)
                    is DequeueWaiter -> dequeues.remove(waiter)
                }
                return cause ?: Exception("-- unexpected call to handleCancellation --")
            }
            return null
        }
    }

    private fun computeEnqueues() {
        mutex.lock()
        while (enqueues.isNotEmpty() && messages.size < capacity) {
            val waiter = enqueues.removeFirst()
            messages.add(waiter.message)
            waiter.done = true
            mutex.unlock()
            waiter.continuation?.resume(Unit)
            mutex.lock()
        }
        mutex.unlock()
    }

    private fun computeDequeues() {
        mutex.lock()
        while (dequeues.isNotEmpty() && messages.isNotEmpty()) {
            val waiter = dequeues.removeFirst()
            val message: T = messages.removeFirst()
            waiter.done = true
            mutex.unlock()
            waiter.continuation?.resume(message)
            mutex.lock()
        }
        mutex.unlock()
    }
}