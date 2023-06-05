package pt.isel.pc.set3

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import pt.isel.pc.chat.Server


private val logger = LoggerFactory.getLogger("main")

/**
 * Entry point for the application
 * See [Server] and [ConnectedClient] for a high-level view of the architecture.
 */


fun main(args: Array<String>) {
    logger.info("main started")
    runBlocking {
        // By default, we listen on port 8080 of all interfaces
        val port = if (args.isEmpty() || args[0].toIntOrNull() == null) 8080 else args[0].toInt()
        //logger.info("Process id is = ${ProcessHandle.current().pid()}. Starting echo server at port $port")

        val server = Server("0.0.0.0", port)
        server.CommandHandler()
    }
}

suspend fun Server.CommandHandler() {
    while (true) {
        val line = readln().trim()
        if (line.isNotEmpty() && line.first() == '/') {
            val parameters = line.split(" ")
            when (parameters[0]) {
                "/Shutdown" -> {
                    if (parameters.size > 1) {
                        val delay = parameters[1].toLongOrNull()
                        if (delay != null) {
                            shutdown(delay)
                            break
                        }
                    }
                }
                "/Exit" -> {
                    exit()
                    break
                }
            }
        }
    }
}
