package pt.isel.pc.chat

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory


private val logger = LoggerFactory.getLogger("main")

suspend fun Server.commandHandler() {
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


fun main(args: Array<String>) {
    logger.info("main started")

    runBlocking {
        // By default, we listen on port 8080 of all interfaces
        val port = if (args.isEmpty() || args[0].toIntOrNull() == null) 8080 else args[0].toInt()
        //logger.info("Process id is = ${ProcessHandle.current().pid()}. Starting echo server at port $port")

        val localhost = "0.0.0.0"
        val server = Server(localhost, port, 2)
        server.commandHandler()
    }
}