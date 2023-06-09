package pt.isel.pc.chat.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val logger = LoggerFactory.getLogger("NIO extensions")

fun createServerChannel(address: InetSocketAddress, executor: ExecutorService): AsynchronousServerSocketChannel {
    val group = AsynchronousChannelGroup.withThreadPool(executor)
    val serverSocket = AsynchronousServerSocketChannel.open(group)
    serverSocket.bind(address)
    return serverSocket
}

suspend fun AsynchronousServerSocketChannel.suspendingAccept(): AsynchronousSocketChannel =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            close()
        }
        accept(null, object: CompletionHandler<AsynchronousSocketChannel, Any?> {
            override fun completed(socketChannel: AsynchronousSocketChannel, attachment: Any?) {
                cont.resume(socketChannel)
            }

            override fun failed(error: Throwable, attachment: Any?) {
                cont.resumeWithException(error)
            }
        })
    }

suspend fun AsynchronousSocketChannel.suspendingReadLine(dst: ByteBuffer): Int =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            close()
        }
        read(dst, null, SocketHandler(cont))
    }

suspend fun AsynchronousSocketChannel.suspendingWriteLine(dst: ByteBuffer): Int =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            close()
        }
        write(dst, null, SocketHandler(cont))
    }

private class SocketHandler(val cont: CancellableContinuation<Int>) : CompletionHandler<Int, Any?> {
    override fun completed(result: Int, attachment: Any?) {
        if (cont.isCancelled)
            cont.resumeWithException(CancellationException())
        else cont.resume(result)
    }

    override fun failed(error: Throwable, attachment: Any?) {
        cont.resumeWithException(error)
    }
}