package pt.isel.pc.chat.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.jvm.Throws
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds


class MessageQueue<T>(private val capacity: Int) {

    init {
        require(capacity > 0) { "Capacity must be greater than zero" }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MessageQueue::class.java)
    }

    private sealed class Waiter<T>(var continuation: CancellableContinuation<T>?) {
        var done: Boolean = false
    }

    private class EnqueueWaiter<T>(continuation: CancellableContinuation<Unit>? = null, val message: T) :
        Waiter<Unit>(continuation)

    private class DequeueWaiter<T>(continuation: CancellableContinuation<T>? = null, val message: T? = null) :
        Waiter<T>(continuation)

    private val mutex = ReentrantLock()
    private val messages = LinkedList<T>()
    private val enqueues = LinkedList<EnqueueWaiter<T>>()
    private val dequeues = LinkedList<DequeueWaiter<T>>()

    @Throws(CancellationException::class)
    suspend fun dequeue(timeout: Duration): T {
        val waiter = DequeueWaiter<T>()
        return try {
            withTimeout(timeout.coerceAtLeast(1.nanoseconds)) {
                suspendCancellableCoroutine { cont ->
                    mutex.withLock {
                        //fast-path
                        if (messages.isNotEmpty() && dequeues.isEmpty()) {
                            val message = messages.removeFirst()
                            computeEnqueues()
                            cont.resume(message)
                        } else {
                            waiter.continuation = cont
                            dequeues.add(waiter)
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            handleCancellationDequeue(ce, waiter) ?: throw IllegalStateException()
        }
    }


    @Throws(CancellationException::class)
    suspend fun enqueue(message: T) {
        val waiter = EnqueueWaiter(message = message)
        return try {
            suspendCancellableCoroutine { cont ->
                //fast path
                mutex.withLock {
                    if (enqueues.isEmpty() && messages.size < capacity) {
                        messages.add(message)
                        computeDequeues()
                        cont.resume(Unit)
                    } else {
                        //suspend-path
                        waiter.continuation = cont
                        enqueues.add(waiter)
                    }
                }
            }
        } catch (ce: CancellationException) {
            handleCancellationEnqueue(ce, waiter)
        }
    }

    private fun handleCancellationEnqueue(cause: Throwable?, waiter: EnqueueWaiter<T>) =
        mutex.withLock {
            if (!waiter.done) {
                waiter.done = true
                enqueues.remove(waiter)
                throw cause ?: Exception("-- unexpected call to handleCancellation --")
            }
        }

    private fun handleCancellationDequeue(cause: Throwable?, waiter: DequeueWaiter<T>): T? {
        mutex.withLock {
            if (!waiter.done) {
                waiter.done = true
                dequeues.remove(waiter)
                throw cause ?: Exception("-- unexpected call to handleCancellation --")
            }
            return waiter.message
        }
    }

    private fun computeEnqueues() {
        mutex.withLock {
            if (enqueues.isNotEmpty() && messages.size < capacity) {
                val waiter = enqueues.removeFirst()
                messages.add(waiter.message)
                waiter.done = true
                waiter.continuation?.resume(Unit)
            }
        }
    }

    private fun computeDequeues() {
        mutex.withLock {
            if (dequeues.isNotEmpty() && messages.isNotEmpty()) {
                val waiter = dequeues.removeFirst()
                val message: T = messages.removeFirst()
                waiter.done = true
                waiter.continuation?.resume(message)
            }
        }
    }
}