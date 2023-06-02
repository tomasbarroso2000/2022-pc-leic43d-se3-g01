package pt.isel.pc.chat

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.utils.MessageQueue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds


class MessageQueueTests {
    companion object {
        private val logger = LoggerFactory.getLogger(MessageQueueTests::class.java)
    }

    @Test
    fun `call enqueue with available permits`() {
        val messages = MessageQueue<String>(1)

        runBlocking {
            messages.enqueue("Hello")
            logger.info("after enqueue")
        }
    }

    @Test
    fun `call enqueue and dequeue`() {
        val messages = MessageQueue<String>(1)

        runBlocking {
            launch {
                messages.enqueue("Hello")
                logger.info("after enqueue")
            }
            launch {
                val message = messages.dequeue(10.seconds)
                logger.info("Message: $message")
                assertEquals("Hello", message)
            }
        }
    }

    @Test
    fun `test enqueue dequeue 2`() = runBlocking {
        val messageQueue = MessageQueue<Int>(3)

        val enqueueJob = launch {
            repeat(3) {
                messageQueue.enqueue(it)
                logger.info("after enqueue of $it")
            }
        }

        val dequeueJob = launch {
            repeat(3) {
                val message = messageQueue.dequeue(1.seconds)
                logger.info("after dequeue of $it")
                assertEquals(it, message)
            }
        }

        enqueueJob.join()
        dequeueJob.join()
    }

    @Test
    fun `test enqueue dequeue 3`() = runBlocking {
        val messageQueue = MessageQueue<Int>(1)

        val enqueueJob = launch {
            repeat(2) {
                messageQueue.enqueue(it)
                logger.info("after enqueue of $it")
                delay(100) // Introduce delay between enqueue operations
            }
        }

        val dequeueJob = launch {
            repeat(2) {
                val message = messageQueue.dequeue(1.seconds)
                logger.info("after dequeue of $it")
                assertEquals(it, message)
                delay(100) // Introduce delay between dequeue operations
            }
        }

        enqueueJob.join()
        dequeueJob.join()
    }

    @Test
    fun `test enqueue capacity reached`() = runBlocking {
        val messageQueue = MessageQueue<Int>(2)

        val enqueueJob = launch {
            repeat(3) {
                messageQueue.enqueue(it)
            }
        }

        val dequeueJob = launch {
            repeat(2) {
                val message = messageQueue.dequeue(1.seconds)
                assertEquals(it, message)
            }
        }

        enqueueJob.join()
        dequeueJob.join()
    }

    @Test
    fun testDequeueTimeout() = runBlocking {
        val messageQueue = MessageQueue<Int>(3)

        val dequeueJob = launch {
            assertFailsWith<TimeoutCancellationException> {
                val message = messageQueue.dequeue(2.seconds)
                assertEquals(0, message)
            }
        }

        dequeueJob.join()
    }

    @Test
    fun testEnqueueCancellation() = runBlocking {
        val messageQueue = MessageQueue<Int>(3)

        val enqueueJob = launch {
            val job = launch {
                delay(2000) // Introduce a delay to allow enqueue coroutine to suspend
                assertFailsWith<CancellationException> {
                    messageQueue.enqueue(10)
                }
            }
            delay(1000) // Wait for enqueue to start
            job.cancel()
        }

        enqueueJob.join()
    }
}