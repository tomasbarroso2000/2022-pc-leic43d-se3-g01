package pt.isel.pc.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.utils.*
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.assertEquals


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
            val j1 = launch {
                // Start the server
                val serverChannel = createServerChannel(address, executor)
                logger.info("Server started, listening on port $port")

                // Accept a client connection
                val clientChannel = serverChannel.suspendingAccept()
                logger.info("Accepted client connection")

                val bufChannel = BufferedSocketChannel(clientChannel)

                // Read the message from the client
                val message = bufChannel.readLine()
                logger.info("Received message from client: $message")

                assertEquals("Hello World", message)

                bufChannel.writeLine("Hello World")

                // Close the client channel
                clientChannel.close()

                // Close the server channel
                serverChannel.close()
                logger.info("Server closed")
            }
        }
    }
}