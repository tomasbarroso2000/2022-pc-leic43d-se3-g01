package pt.isel.pc.set3.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
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

suspend fun AsynchronousServerSocketChannel.suspendingAccept(): AsynchronousSocketChannel =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            logger.info("Suspending accept cancelled")
            close()
            continuation.resumeWithException(CancellationException("Suspending accept cancelled"))
        }
        accept(null, object : CompletionHandler<AsynchronousSocketChannel, Any?> {
            override fun completed(socket: AsynchronousSocketChannel, attachment: Any?) {
                logger.info("Accepted client connection")
                continuation.resume(socket)
            }

            override fun failed(error: Throwable, attachment: Any?) {
                logger.error("Failed to accept client connection", error)
                continuation.resumeWithException(error)
            }
        })
    }

suspend fun AsynchronousSocketChannel.suspendingWriteLine(line: String, timeout: Long = Long.MAX_VALUE, unit: TimeUnit = TimeUnit.MINUTES): Int =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            logger.info("Suspending write cancelled")
            close()
            continuation.resumeWithException(CancellationException("Suspending write cancelled"))
        }

        val toSend = CharBuffer.wrap(line + "\n")
        writeChunk(toSend, 0, timeout, unit, continuation)
    }

//deal with the case when not all the string's chars are written in one call
private fun AsynchronousSocketChannel.writeChunk(
    toSend: CharBuffer,
    total: Int,
    timeout: Long,
    unit: TimeUnit,
    continuation: CancellableContinuation<Int>
) {
    write(encoder.encode(toSend), timeout, unit, null, object : CompletionHandler<Int, Any?> {
        override fun completed(result: Int, attachment: Any?) {
            if (toSend.hasRemaining()) {
                writeChunk(toSend, total + result, timeout, unit, continuation)
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

suspend fun AsynchronousSocketChannel.suspendingReadLine(
    timeout: Long = Long.MAX_VALUE,
    unit: TimeUnit = TimeUnit.MINUTES
): String? {
    return suspendCancellableCoroutine { continuation ->
        val buffer = ByteBuffer.allocate(1024)
        val lineBuffer = StringBuilder()

        continuation.invokeOnCancellation {
            logger.info("Suspending read cancelled")
            close()
            continuation.resumeWithException(CancellationException("Suspending read cancelled"))
        }

        readChunk(buffer, lineBuffer, timeout, unit, continuation)
    }
}

private fun AsynchronousSocketChannel.readChunk(
    buffer: ByteBuffer,
    lineBuffer: StringBuilder,
    timeout: Long,
    unit: TimeUnit,
    continuation: CancellableContinuation<String?>
) {
    read(buffer, timeout, unit, null, object : CompletionHandler<Int, Any?> {
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
                    readChunk(buffer, lineBuffer, timeout, unit, continuation)
                }
            }
        }

        override fun failed(error: Throwable, attachment: Any?) {
            logger.error("Read failed.")
            if (error is InterruptedByTimeoutException) {
                continuation.resume(null)
            } else {
                continuation.resumeWithException(error)
            }
        }
    })
}