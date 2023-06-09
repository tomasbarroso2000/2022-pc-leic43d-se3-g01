package pt.isel.pc.chat

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.domain.ServerRequest
import pt.isel.pc.chat.domain.ServerRequest.ShutdownCommand

private const val LISTENING_ADDRESS = "0.0.0.0"
private const val DEFAULT_LISTENING_PORT = 8080
private const val MAX_CLIENTS = 2

private val logger = LoggerFactory.getLogger("main")

private suspend fun Server.commandHandler() {
    while (true) {
        when (val command = ServerRequest.parse(readln().trim())) {
            is ShutdownCommand -> {
                val delay = command.timeout.toLongOrNull()
                if (delay != null) {
                    shutdown(delay)
                    break
                }
            }
            is ServerRequest.ExitCommand -> {
                exit()
                break
            }
            is ServerRequest.InvalidRequest -> {
                logger.info("Invalid command")
            }
        }
    }
}

fun main(args: Array<String>) = runBlocking {
    logger.info("main started")
    val port = if (args.isEmpty() || args[0].toIntOrNull() == null) DEFAULT_LISTENING_PORT else args[0].toInt()
    val server = Server(LISTENING_ADDRESS, port, MAX_CLIENTS)
    server.commandHandler()
}