package pt.isel.pc.chat

import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.domain.ConnectedClient
import pt.isel.pc.chat.domain.Messages
import pt.isel.pc.chat.utils.TestClient
import pt.isel.pc.chat.utils.TestHelper
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds


class MessagingTests {

    companion object {
        private val logger = LoggerFactory.getLogger(MessagingTests::class.java)
    }

    @Test
    fun `first scenario`() {
        // given: a set of clients
        val nOfClients = 5
        val clients = List(nOfClients) {
            TestClient()
        }

        Server("0.0.0.0", 8080, nOfClients).use { server ->
            // and: a server listening
            server.waitUntilListening()

            clients.forEach {
                // when: a client connects and requests to enter a room
                it.connect()
                it.send("/enter lounge")
                // then: it receives a success message
                assertEquals(Messages.enteredRoom("lounge"), it.receive())
            }

            val nRepeats = AtomicInteger(nOfClients - 1)

            //clean all enter room messages from different clients
            (0 until nOfClients).map { clientId ->
                repeat(nRepeats.get()) {
                    clients[clientId].receive()
                }
                nRepeats.decrementAndGet()
            }

            // when: client 0 sends a message
            clients[0].send("Hi there.")
            clients.forEach {
                if (it != clients[0]) // then: all clients, other than client 0, receive the message
                    assertEquals(Messages.messageFromClient("client-1", "Hi there."), it.receive())
            }

            // when: client 1 sends a message
            clients[1].send("Hello.")
            clients.forEach {
                if (it != clients[1]) // then: all clients, other than client 1, receive the message
                    assertEquals(Messages.messageFromClient("client-2", "Hello."), it.receive())
            }
            clients.forEach {
                // when: all clients ask to exit
                it.send("/exit")
                // then: all clients receive the exit acknowledgment
                assertEquals(Messages.BYE, it.receive())
            }
        }
    }

    /**
     * Stress test where a large number of clients send a large number of messages and we
     * check that each client received all the messages from all other clients.
     */
    @Test
    fun `stress test`() {
        // given:
        val nOfClients = 40
        val nOfMessages = 20
        val delayBetweenMessagesInMillis = 0L
        val testHelper = TestHelper(30.seconds)
        val counter = ConcurrentHashMap<String, AtomicLong>()

        // and: a set of clients
        val clients = List(nOfClients) {
            TestClient()
        }

        Server("0.0.0.0", 8080, nOfClients).use { server ->
            // and: a listening server
            server.waitUntilListening()

            // when: all clients connect and enter the same room
            clients.forEach {
                it.connect()
                it.send("/enter lounge")
                assertEquals(Messages.enteredRoom("lounge"), it.receive())
            }

            val nRepeats = AtomicInteger(nOfClients - 1)

            //clean all enter room messages from different clients
            (0 until nOfClients).map { clientId ->
                repeat(nRepeats.get()) {
                    clients[clientId].receive()
                }
                nRepeats.decrementAndGet()
            }

            clients.forEach { client ->
                testHelper.createAndStart(0) { _, _ ->
                    // Helper thread to read all messages sent to a client ...
                    val readThread = testHelper.createAndStart {
                        var receivedMessages = 0
                        while (true) {
                            try {
                                val msg = client.receive() ?: break

                                // ... and updated a shared map with an occurrence counter for each message
                                counter.computeIfAbsent(msg) { AtomicLong() }.incrementAndGet()

                                // ... when all the expected messages are receives, we end the thread
                                if (++receivedMessages == (nOfClients - 1) * nOfMessages)
                                    break
                            } catch (ex: SocketTimeoutException) {
                                throw RuntimeException("timeout with '$receivedMessages' received messages", ex)
                            }
                        }
                    }
                    // and: all the messages are sent, with an optional delay between messages
                    (1..nOfMessages).forEach { index ->
                        client.send("message-$index")
                        if (delayBetweenMessagesInMillis != 0L) {
                            Thread.sleep(delayBetweenMessagesInMillis)
                        }
                    }

                    // and: the reader thread ended, meaning all expected messages were received
                    readThread.join()

                    // and: we ask the client to exit
                    client.send("/exit")
                    assertEquals(Messages.BYE, client.receive())
                }
            }
            testHelper.join()
            // then: Each sent message was received (nOfClients - 1) times.
            (1..nOfClients).forEach {
                val clientId = "client-$it"
                (1..nOfMessages).forEach { index ->
                    val message = Messages.messageFromClient(clientId, "message-$index")
                    val counts = counter[message]
                    assertNotNull(counts, "counter for message '$message' must not be null")
                    assertEquals((nOfClients - 1).toLong(), counts.get())
                }
            }
        }
    }
}