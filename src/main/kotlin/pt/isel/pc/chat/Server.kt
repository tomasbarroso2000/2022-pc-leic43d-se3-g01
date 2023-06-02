package pt.isel.pc.set3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.domain.ConnectedClient
import pt.isel.pc.chat.domain.ConnectedClientContainer
import pt.isel.pc.chat.domain.Messages
import pt.isel.pc.chat.domain.RoomContainer
import pt.isel.pc.set3.utils.createServerChannel
import pt.isel.pc.set3.utils.suspendingAccept
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * Represents a server to which clients can connect, enter and leave rooms, and send messages.
 */
class Server(
    private val listeningAddress: String,
    private val listeningPort: Int,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : AutoCloseable {

    private val serverSocket: AsynchronousServerSocketChannel =
        createServerChannel(InetSocketAddress(listeningAddress, listeningPort), executor)

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * The listening thread is mainly comprised by loop waiting for connections and creating a [ConnectedClient]
     * for each accepted connection.
     */
    private val listeningThread = scope.launch {
        serverSocket.use { serverSocket ->
            //serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
            logger.info("server socket bound to ({}:{})", listeningAddress, listeningPort)
            println(Messages.SERVER_IS_BOUND)
            acceptLoop(serverSocket)
        }
    }

    fun shutdown() {
        // Currently, the only way to unblock the listening thread from the listen method is by closing
        // the server socket.
        logger.info("closing server socket as a way to 'interrupt' the listening thread")
        serverSocket.close()
    }

    suspend fun join() = listeningThread.join()

    override fun close(){
        scope.launch {
            shutdown()
            join()
        }
    }

    private suspend fun acceptLoop(serverSocket: AsynchronousServerSocketChannel) {
        var clientId = 0
        val roomContainer = RoomContainer()
        val clientContainer = ConnectedClientContainer()
            while (true) {
                try {
                    // TODO: throttling
                    logger.info("accepting new client")

                    val socket: AsynchronousSocketChannel = serverSocket.suspendingAccept()
                    logger.info("client socket accepted, remote address is {}", socket.remoteAddress)
                    println(Messages.SERVER_ACCEPTED_CLIENT)

                    val client = ConnectedClient(socket, ++clientId, roomContainer, scope , clientContainer)
                    clientContainer.add(client)
                } catch (ex: SocketException) {
                    logger.info("SocketException, ending")
                    // We assume that an exception means the server was asked to terminate
                    break
                }
            }
        clientContainer.shutdown()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Server::class.java)
    }
}