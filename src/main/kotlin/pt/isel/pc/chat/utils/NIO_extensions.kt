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
import java.util.concurrent.CancellationException
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

        write(encoder.encode(toSend), null, object : CompletionHandler<Int, Any?> {
            override fun completed(result: Int, attachment: Any?) {
                if (continuation.isCancelled)
                    continuation.resumeWithException(CancellationException())
                else
                    continuation.resume(result)
            }

            override fun failed(error: Throwable, attachment: Any?) {
                continuation.resumeWithException(error)
            }
        })
    }
}

/**
 * Reads a line from this socket channel.
 * @param timeout   The maximum time for the I/O operation to complete
 * @param unit      The time unit of the {@code timeout} argument
 * @return the read line, or null if the operation timed out
 */
suspend fun AsynchronousSocketChannel.suspendingReadLine(timeout: Long = 0, unit: TimeUnit = TimeUnit.MILLISECONDS): String? {
    return suspendCancellableCoroutine { continuation ->
        val buffer = ByteBuffer.allocate(1024)

        read(buffer, timeout, unit, null, object : CompletionHandler<Int, Any?> {
            override fun completed(result: Int, attachment: Any?) {
                if (continuation.isCancelled)
                    continuation.resumeWithException(CancellationException())
                else {
                    val received = decoder.decode(buffer.flip()).toString().trim()
                    continuation.resume(received)
                }
            }

            override fun failed(error: Throwable, attachment: Any?) {
                if (error is InterruptedByTimeoutException)
                    continuation.resume(null)
                else continuation.resumeWithException(error)
            }
        })
    }
}