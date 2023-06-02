package pt.isel.pc.set3

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread
import kotlin.coroutines.resume


private val logger = LoggerFactory.getLogger("main")

/**
 * Entry point for the application
 * See [Server] and [ConnectedClient] for a high-level view of the architecture.
 */

suspend fun shutdownHook(server: Server) =
    suspendCancellableCoroutine<Unit> { cont ->
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            logger.info("shutdown hook started")
            server.shutdown()
            logger.info("waiting for server to end")
            //server.join()
            logger.info("server ended")
            cont.resume(Unit)
        })
    }

fun main(args: Array<String>) {
    logger.info("main started")
    runBlocking {
        // By default, we listen on port 8080 of all interfaces
        val port = if (args.isEmpty() || args[0].toIntOrNull() == null) 8080 else args[0].toInt()
        //logger.info("Process id is = ${ProcessHandle.current().pid()}. Starting echo server at port $port")

        val server = Server("0.0.0.0", port)

        // Shutdown hook to handle SIG_TERM signals (gracious shutdown)
        shutdownHook(server)

        server.join()
    }
}