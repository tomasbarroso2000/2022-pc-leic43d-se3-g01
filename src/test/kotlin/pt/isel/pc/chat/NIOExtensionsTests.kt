package pt.isel.pc.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.utils.createServerChannel
import pt.isel.pc.chat.utils.suspendingAccept
import pt.isel.pc.chat.utils.suspendingReadLine
import pt.isel.pc.chat.utils.suspendingWriteLine
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class NIOExtensionsTests {

    companion object {
        private val logger = LoggerFactory.getLogger(NIOExtensionsTests::class.java)
    }

    @Test
    fun `test suspendingReadLine`() {
        val port = 8080
        val address = InetSocketAddress("localhost", port)
        val executor: ExecutorService = Executors.newSingleThreadExecutor()

        runBlocking {
            launch {
                // Start the server
                val serverChannel = createServerChannel(address, executor)
                logger.info("Server started, listening on port $port")

                // Accept a client connection
                val clientChannel = serverChannel.suspendingAccept()
                logger.info("Accepted client connection")

                // Read the message from the client
                val message = clientChannel.suspendingReadLine()
                logger.info("Received message from client: $message")

                // Echo the message back to the client
                message?.let { clientChannel.suspendingWriteLine(it) }
                logger.info("Sent message back to client")

                // Close the client channel
                clientChannel.close()

                // Close the server channel
                serverChannel.close()
                logger.info("Server closed")
            }

            // Start the client
            launch {
                delay(100) // Wait for the server to start accepting connections

                // Connect to the server
                val clientChannel = AsynchronousSocketChannel.open()
                clientChannel.connect(address).get() // Connect to the server
                logger.info("Connected to server")

                // Send a message to the server
                val message = "Hello from client!"
                clientChannel.suspendingWriteLine(message)
                logger.info("Sent message to server")

                // Read the response from the server
                val response = clientChannel.suspendingReadLine()
                logger.info("Received response from server: $response")

                // Close the client channel
                clientChannel.close()
            }
        }
    }
}