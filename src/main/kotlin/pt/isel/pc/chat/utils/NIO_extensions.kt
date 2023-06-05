package pt.isel.pc.chat.utils

import kotlinx.coroutines.CancellableContinuation
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.channels.InterruptedByTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


private val logger = LoggerFactory.getLogger("NIO extensions")
private val encoder = Charsets.UTF_8.newEncoder()
private val decoder = Charsets.UTF_8.newDecoder()

fun createServerChannel(address: InetSocketAddress, executor: ExecutorService): AsynchronousServerSocketChannel {
    val group = AsynchronousChannelGroup.withThreadPool(executor)
    val serverSocket = AsynchronousServerSocketChannel.open(group)
    serverSocket.bind(address)
    return serverSocket
}

suspend fun AsynchronousServerSocketChannel.suspendingAccept(): AsynchronousSocketChannel {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            logger.info("Suspending accept cancelled")
            close()
        }
        accept(null, object : CompletionHandler<AsynchronousSocketChannel, Any?> {
            override fun completed(socketChannel: AsynchronousSocketChannel, attachment: Any?) {
                continuation.resume(socketChannel)
            }

            override fun failed(error: Throwable, attachment: Any?) {
                continuation.resumeWithException(error)
            }
        })
    }
}

suspend fun AsynchronousSocketChannel.suspendingWriteLine(line: String): Int =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            logger.info("Suspending write cancelled")
            close()
        }

        val toSend = CharBuffer.wrap(line + "\n")
        writeChunk(toSend, 0, continuation)
    }

//deal with the case when not all the string's chars are written in one call
private fun AsynchronousSocketChannel.writeChunk(
    toSend: CharBuffer,
    total: Int,
    continuation: CancellableContinuation<Int>
) {
    write(encoder.encode(toSend), null, object : CompletionHandler<Int, Any?> {
        override fun completed(result: Int, attachment: Any?) {
            if (toSend.hasRemaining()) {
                writeChunk(toSend, total + result, continuation)
            } else {
                logger.info("Write succeeded.")
                continuation.resume(total + result)
            }
        }

        override fun failed(error: Throwable, attachment: Any?) {
            logger.error("Write failed.")
            continuation.resumeWithException(error)
        }
    })
}

suspend fun AsynchronousSocketChannel.suspendingReadLine(): String? {
    return suspendCancellableCoroutine { continuation ->
        val buffer = ByteBuffer.allocate(1024)
        val lineBuffer = StringBuilder()

        continuation.invokeOnCancellation {
            logger.info("Suspending read cancelled")
            close()
        }

        readChunk(buffer, lineBuffer, continuation)
    }
}

private fun AsynchronousSocketChannel.readChunk(
    buffer: ByteBuffer,
    lineBuffer: StringBuilder,
    continuation: CancellableContinuation<String?>
) {
    read(buffer, null, object : CompletionHandler<Int, Any?> {
        override fun completed(result: Int, attachment: Any?) {
            if (result == -1) {
                logger.info("End of stream reached.")
                continuation.resume(null)
            } else {
                buffer.flip() // Flip the buffer to prepare for reading
                val received = decoder.decode(buffer).toString()
                lineBuffer.append(received)

                val line = lineBuffer.toString().substringBeforeLast('\n').substringBeforeLast('\r').trim()
                val remaining = lineBuffer.toString().substringAfterLast('\n').substringAfterLast('\r')

                if (remaining.isEmpty()) {
                    // The line is complete
                    lineBuffer.clear()
                    logger.info("Read succeeded.")
                    continuation.resume(line)
                } else {
                    buffer.clear()
                    lineBuffer.setLength(0)
                    lineBuffer.append(remaining)
                    logger.info("Read succeeded, but more to read.")
                    readChunk(buffer, lineBuffer, continuation)
                }
            }
        }

        override fun failed(error: Throwable, attachment: Any?) {
            logger.error("Read failed.")
            continuation.resumeWithException(error)
        }
    })
}