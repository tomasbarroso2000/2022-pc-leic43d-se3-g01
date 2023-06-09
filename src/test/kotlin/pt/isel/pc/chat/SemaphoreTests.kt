package pt.isel.pc.chat

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.utils.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


class SemaphoreTests {

    companion object {
        private val logger = LoggerFactory.getLogger(SemaphoreTests::class.java)
    }

    @Test
    fun `call acquire with available permits`() {
        val sem = Semaphore(2)

        runBlocking {
            sem.acquire(Duration.INFINITE)
            logger.info("after acquire")
        }
    }

    @Test
    fun `call acquire without available permits`() {
        val sem = Semaphore(1)
        val count = AtomicInteger()

        runBlocking {
            repeat(2) {
                launch {
                    logger.info("before acquire 1")
                    sem.acquire(Duration.INFINITE)
                    logger.info("after acquire 1")
                    count.incrementAndGet()
                }
            }

            repeat(2) {
                launch {
                    delay(1000)
                    logger.info("before sem release")
                    sem.release()
                    logger.info("after sem release")
                    count.decrementAndGet()
                }
            }
        }

        assertEquals(0, count.get())
    }

    @Test
    fun `call acquire with timeout`() {
        val sem = Semaphore(1)

        runBlocking {
            val acquirer1 = launch {
                logger.info("before acquire 1")
                sem.acquire(Duration.INFINITE)
                logger.info("after acquire 1")
            }

            val acquirer2 = launch {
                logger.info("before acquire 2")
                assertFailsWith<TimeoutCancellationException> {
                    sem.acquire(2.seconds)
                }
                logger.info("after acquire 2")
            }

            acquirer1.join()
            acquirer2.join()
        }
    }

    @Test
    fun `test release without waiters`() = runBlocking {
        val semaphore = Semaphore(2)

        semaphore.release()
        semaphore.release()
        val result = withTimeoutOrNull(1.seconds) {
            semaphore.acquire(Duration.INFINITE)
        }
        assertNotNull(result)
    }

    @Test
    fun `test release with waiters`() = runBlocking {
        val semaphore = Semaphore(1)

        val acquireJob = launch {
            semaphore.acquire(Duration.INFINITE)
        }

        delay(100) // Wait for the acquire operation to start

        semaphore.release()

        acquireJob.join() // Ensure the acquire operation completes successfully
    }

    @Test
    fun `test acquire with timeout`(){
        runBlocking {
            val semaphore = Semaphore(0)

            assertFailsWith<TimeoutCancellationException> {
                semaphore.acquire(1.seconds)
            }
        }
    }

    @Test
    fun `test acquire cancellation`() {
        runBlocking {
            val semaphore = Semaphore(0)

            val acquireJob = launch {
                assertFailsWith<CancellationException> {
                    semaphore.acquire(Duration.INFINITE)
                }
            }

            val cancelJob = launch { acquireJob.cancel() }

            acquireJob.join()
            cancelJob.join()
        }
    }
}