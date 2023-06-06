package pt.isel.pc.chat.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
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
        accept(null, object : CompletionHandler<AsynchronousSocketChannel, Any?> {
            override fun completed(socketChannel: AsynchronousSocketChannel, attachment: Any?) {
                continuation.resume(socketChannel)
            }

            override fun failed(error: Throwable, attachment: Any?) {
                logger.info("Suspending accept failed {}", error.message)
                continuation.resumeWithException(error)
            }
        })
    }
}

/**
 * Writes the given text to this socket channel.
 * @param text  The text to be written
 * @return the number of written bytes.
 */
suspend fun AsynchronousSocketChannel.suspendingWriteLine(text: String): Int {
    return suspendCancellableCoroutine { continuation ->
        val toSend = CharBuffer.wrap(text + "\r\n")
        writeChunk(toSend, 0, continuation)
    }
}

private fun AsynchronousSocketChannel.writeChunk(
    toSend: CharBuffer,
    total: Int,
    continuation: CancellableContinuation<Int>
) {
    write(encoder.encode(toSend), null, object : CompletionHandler<Int, Any?> {
        override fun completed(result: Int, attachment: Any?) {
            if (continuation.isCancelled)
                continuation.resumeWithException(CancellationException())
            else if (toSend.hasRemaining())
                writeChunk(toSend, total + result, continuation)
            else
                continuation.resume(total + result)
        }

        override fun failed(error: Throwable, attachment: Any?) {
            logger.info("Suspending write failed {}", error.message)
            continuation.resumeWithException(error)
        }
    })
}

suspend fun AsynchronousSocketChannel.suspendingReadLine(): String? {
    return suspendCancellableCoroutine { continuation ->
        val buffer = ByteBuffer.allocate(1024)
        val lineBuffer = StringBuilder()
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
                val received = decoder.decode(buffer.flip()).toString()
                lineBuffer.append(received)

                val line = lineBuffer.toString().substringBeforeLast('\n').substringBeforeLast('\r').trim()
                val remaining = lineBuffer.toString().substringAfterLast('\n').substringAfterLast('\r')

                if (remaining.isEmpty()) {
                    // The line is complete
                    lineBuffer.clear()
                    continuation.resume(line)
                } else {
                    buffer.clear()
                    lineBuffer.setLength(0)
                    lineBuffer.append(remaining)
                    readChunk(buffer, lineBuffer, continuation)
                }
            }
        }

        override fun failed(error: Throwable, attachment: Any?) {
            logger.info("Suspending read failed {}", error.message)
            continuation.resumeWithException(error)
        }
    })
}