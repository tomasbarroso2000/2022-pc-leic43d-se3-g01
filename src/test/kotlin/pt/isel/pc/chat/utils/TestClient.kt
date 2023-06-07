package pt.isel.pc.chat.utils

import pt.isel.pc.chat.domain.Messages
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.assertEquals


class TestClient {
    private val socket = Socket()

    init {
        socket.soTimeout = 5_000
    }

    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    fun connect() {
        socket.connect(InetSocketAddress("127.0.0.1", 8080))
        reader = socket.getInputStream().bufferedReader()
        writer = socket.getOutputStream().bufferedWriter()
        assertEquals(Messages.CLIENT_WELCOME, receive())
    }

    fun send(msg: String) {
        val observed = writer
        requireNotNull(observed) {"Observed ($observed) cannot be null!"}
        observed.write(msg)
        observed.newLine()
        observed.flush()
    }

    fun receive(): String? {
        val observed = reader
        requireNotNull(observed) {"Observed ($observed) cannot be null!"}
        return observed.readLine()
    }
}