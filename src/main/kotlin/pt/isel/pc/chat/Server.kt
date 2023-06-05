package pt.isel.pc.chat

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
import java.lang.Thread.sleep
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess


/**
 * Represents a server to which clients can connect, enter and leave rooms, and send messages.
 */
class Server(
    private val listeningAddress: String,
    private val listeningPort: Int,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : AutoCloseable {

    enum class State{ NOT_STARTED, STARTED, STOPPING, STOPPED }

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

    private val scope = CoroutineScope(Dispatchers.IO)

    private val roomContainer = RoomContainer()
    private val clientContainer = ConnectedClientContainer()


    /**
     * The listening thread is mainly comprised by loop waiting for connections and creating a [ConnectedClient]
     * for each accepted connection.
     */
    private val acceptCoroutine = scope.launch {
        serverSocket = createServerSocketChannel()
        serverSocket.use { serverSocket ->
            logger.info("server socket bound to ({}:{})", listeningAddress, listeningPort)
            state = State.STARTED
            println(Messages.SERVER_IS_BOUND)
            acceptLoop(serverSocket)
        }
    }

    suspend fun shutdown(timeout : Long) {
        guard.withLock {
            if (state != State.STARTED) {
                throw IllegalStateException("Server hasn't started or has been stopped")
            }
            state = State.STOPPING
        }
        clientContainer.shutdown()
        acceptCoroutine.cancelAndJoin()
        //serverSocket.close()
        executor.shutdown()
        val ended = executor.awaitTermination(timeout, TimeUnit.SECONDS)
        if(!ended)
            exit()
        else {
            guard.withLock {
                if(state != State.STOPPING) {
                    throw IllegalStateException("Server can't stop")
                }
                state = State.STOPPED
            }
        }
    }

    suspend fun join() {
        guard.withLock {
            if(state == State.NOT_STARTED || state == State.STOPPED)
                throw IllegalStateException("Server is not active")
        }
        acceptCoroutine.join()
    }

    suspend fun exit() {
        guard.withLock {
            if (state == State.NOT_STARTED || state == State.STOPPED) {
                throw IllegalStateException("Server hasn't started or has been stopped")
            }
            state = State.STOPPED
        }
        exitProcess(0)
    }
    override fun close(){
        scope.launch {
            shutdown(5000)
        }
    }

    suspend fun isStarted() = guard.withLock {  state == State.STARTED }

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
        catch (ex: ClosedChannelException) {
            logger.info("Server is shutting down")
        }

    }
    companion object {
        private val logger = LoggerFactory.getLogger(Server::class.java)
    }
}