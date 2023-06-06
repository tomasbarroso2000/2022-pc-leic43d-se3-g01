package pt.isel.pc.chat.domain

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.utils.MessageQueue
import pt.isel.pc.set3.domain.Room
import pt.isel.pc.chat.utils.suspendingReadLine
import pt.isel.pc.chat.utils.suspendingWriteLine
import java.io.IOException
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

private const val NR_MAX_MESSAGES = 50

/**
 * Responsible for handling a single connected client. It is comprised by two threads:
 * - `readLoopThread` - responsible for (blocking) reading lines from the client socket. It is the only thread that
 *    reads from the client socket.
 * - `mainLoopThread` - responsible for handling control messages sent from the outside
 *    or from the inner `readLoopThread`. It is the only thread that writes to the client socket.
 */
class ConnectedClient(
    private val socket: AsynchronousSocketChannel,
    id: Int,
    private val roomContainer: RoomContainer,
    private val scope: CoroutineScope,
    private val clientContainer: ConnectedClientContainer,
) {

    private val name = "client-$id"

    // The control messages the main loop handles...
    private sealed interface ControlMessage {
        // ... a message sent by a room
        data class RoomMessage(val sender: ConnectedClient, val message: String) : ControlMessage

        // ... a line sent by the connected remote client
        data class RemoteClientRequest(val request: ClientRequest) : ControlMessage

        // ... the connected remote client closes the socket (local receive)
        object RemoteInputClosed : ControlMessage

        // ... the shutdown method was called
        object Shutdown : ControlMessage

        // ... the stop method was called

        object Stop : ControlMessage
    }

    private var mainLoop: Job = mainLoop()
    private var readLoop: Job = readLoop()

    suspend fun send(sender: ConnectedClient, message: String) {
        // just add a control message into the control queue
        controlQueue.enqueue(ControlMessage.RoomMessage(sender, message))
    }

    suspend fun shutdown() {
        // just add a control message into the control queue
        controlQueue.enqueue(ControlMessage.Shutdown)
    }

    suspend fun stop() {
        controlQueue.enqueue(ControlMessage.Stop)
    }

    suspend fun join() = mainLoop.join()


    private val controlQueue = MessageQueue<ControlMessage>(NR_MAX_MESSAGES)

    private var room: Room? = null

    private fun mainLoop(): Job {
        return scope.launch {
            logger.info("[{}] main loop started", name)
            socket.use {
                it.suspendingWriteLine(Messages.CLIENT_WELCOME)
                try {
                    while (true) {
                        when (val control = controlQueue.dequeue(Duration.INFINITE)) {
                            is ControlMessage.Shutdown -> {
                                logger.info("[{}] received control message: {}", name, control)
                                it.suspendingWriteLine(Messages.SERVER_SHUTDOWN)
                            }
                            is ControlMessage.Stop -> {
                                it.suspendingWriteLine(Messages.SERVER_IS_ENDING)
                                break
                            }

                            is ControlMessage.RoomMessage -> {
                                logger.trace("[{}] received control message: {}", name, control)
                                it.suspendingWriteLine(Messages.messageFromClient(control.sender.name, control.message))
                            }

                            is ControlMessage.RemoteClientRequest -> {
                                val line = control.request
                                if (handleRemoteClientRequest(line, it)) break
                            }

                            ControlMessage.RemoteInputClosed -> {
                                logger.info("[{}] received control message: {}", name, control)
                                break
                            }
                        }
                    }

                } catch(ex: Exception) {
                    logger.info("apanhar accept exception")
                }
            }
            clientContainer.remove(this@ConnectedClient)
            readLoop.cancelAndJoin()
            logger.info("[{}] main loop ending", name)
        }
    }

    private suspend fun handleRemoteClientRequest(
        clientRequest: ClientRequest,
        socket: AsynchronousSocketChannel
    ): Boolean {
            when (clientRequest) {
                is ClientRequest.EnterRoomCommand -> {
                    logger.info("[{}] received remote client request: {}", name, clientRequest)
                    room?.remove(this)
                    room = roomContainer.getByName(clientRequest.name).also {
                        it.add(this)
                    }
                    socket.suspendingWriteLine(Messages.enteredRoom(clientRequest.name))
                }

                ClientRequest.LeaveRoomCommand -> {
                    logger.info("[{}] received remote client request: {}", name, clientRequest)
                    room?.remove(this)
                    room = null
                }

                ClientRequest.ExitCommand -> {
                    logger.info("[{}] received remote client request: {}", name, clientRequest)
                    room?.remove(this)
                    socket.suspendingWriteLine(Messages.BYE)
                    return true
                }

                is ClientRequest.InvalidRequest -> {
                    logger.info("[{}] received remote client request: {}", name, clientRequest)
                    socket.suspendingWriteLine(Messages.ERR_INVALID_LINE)
                }

                is ClientRequest.Message -> {
                    logger.trace("[{}] received remote client request: {}", name, clientRequest)
                    val currentRoom = room
                    if (currentRoom != null) {
                        currentRoom.post(this, clientRequest.value)
                    } else {
                        socket.suspendingWriteLine(Messages.ERR_NOT_IN_A_ROOM)
                    }
                }
            }
                return false
    }

    private fun readLoop() =
        scope.launch {
            try {
                while (true) {
                    val line = socket.suspendingReadLine()
                    if (line == null) {
                        logger.info("[{}] end of input stream reached", name)
                        controlQueue.enqueue(ControlMessage.RemoteInputClosed)
                        break
                    }
                    controlQueue.enqueue(ControlMessage.RemoteClientRequest(ClientRequest.parse(line)))
                }
            } catch (ex: IOException) {
                logger.info("Server shutting down")
                logger.info(ex.message)
            }
            logger.info("[{}] client loop ending", name)
        }

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectedClient::class.java)
    }
}