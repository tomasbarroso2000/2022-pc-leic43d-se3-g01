package pt.isel.pc.set3

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.domain.ConnectedClient
import pt.isel.pc.chat.domain.ConnectedClientContainer
import pt.isel.pc.chat.domain.Messages
import pt.isel.pc.chat.domain.RoomContainer
import pt.isel.pc.set3.utils.createServerChannel
import pt.isel.pc.set3.utils.suspendingAccept
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


/**
 * Represents a server to which clients can connect, enter and leave rooms, and send messages.
 */
class Server(
    private val listeningAddress: String,
    private val listeningPort: Int,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : AutoCloseable {

    enum class State{ NOT_STARTED, STARTED, STOPPING, STOPPED}

    private var state = State.NOT_STARTED

    private val guard = Mutex()

    private suspend fun createServerSocketChannel() : AsynchronousServerSocketChannel {
        guard.withLock {
            if (state == State.NOT_STARTED)
                return createServerChannel(InetSocketAddress(listeningAddress, listeningPort), executor)
            else throw Exception("Already started")
        }
    }

    private lateinit var serverSocket: AsynchronousServerSocketChannel

    private val scope = CoroutineScope(executor.asCoroutineDispatcher())

    private val roomContainer = RoomContainer()
    private val clientContainer = ConnectedClientContainer()


    /**
     * The listening thread is mainly comprised by loop waiting for connections and creating a [ConnectedClient]
     * for each accepted connection.
     */
    private val acceptCoroutine = scope.launch {
        serverSocket = createServerSocketChannel()
        serverSocket.use { serverSocket ->
            //serverSocket.bind(InetSocketAddress(listeningAddress, listeningPort))
            logger.info("server socket bound to ({}:{})", listeningAddress, listeningPort)
            state = State.STARTED
            println(Messages.SERVER_IS_BOUND)
            acceptLoop(serverSocket)
        }
    }

    suspend fun shutdown(timeout : Long) {
        clientContainer.shutdown()
        serverSocket.close()
        //delay(timeout)
        acceptCoroutine.join()
        executor.shutdown()
        executor.awaitTermination(0 , TimeUnit.MILLISECONDS)
        logger.info("closing server socket as a way to 'interrupt' the listening thread")
    }

    suspend fun exit() {
        acceptCoroutine.cancelAndJoin()
    }

    suspend fun join() = acceptCoroutine.join()

    override fun close(){
        scope.launch {
            shutdown(0)
            join()
        }
    }

    suspend fun isStarted() = guard.withLock {  state == State.STARTED  }

    private suspend fun acceptLoop(serverSocket: AsynchronousServerSocketChannel) {
        var clientId = 0
        try {
            while (isStarted()) {
                    // TODO: throttling
                    logger.info("accepting new client")

                    val socket: AsynchronousSocketChannel = serverSocket.suspendingAccept()
                    logger.info("client socket accepted, remote address is {}", socket.remoteAddress)
                    println(Messages.SERVER_ACCEPTED_CLIENT)

                    val client = ConnectedClient(socket, ++clientId, roomContainer, scope , clientContainer)
                    clientContainer.add(client)
                }
        }

        catch (ex: Exception) {
            logger.info("Server is shutting down")
        }

    }


    companion object {
        private val logger = LoggerFactory.getLogger(Server::class.java)
    }
}