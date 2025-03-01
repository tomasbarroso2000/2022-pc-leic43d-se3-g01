package pt.isel.pc.chat.utils

import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.AsynchronousSocketChannel

class BufferedSocketChannel(
    private val channel: AsynchronousSocketChannel,
    private val bufCapacity : Int = BYTE_BUF_SIZE,
    // fill parameter is just for test purposes
    val fill: (ByteBuffer) -> Int = {-2})  : Closeable {

    companion object {
        const val BYTE_BUF_SIZE = 512
        const val CHAR_BUF_SIZE = BYTE_BUF_SIZE*2
        const val MAX_LINE = 256
        private const val NULL_CHAR : Char = 0.toChar()
        private val EMPTY_STAT = LineStat(0,0)
        private val logger = LoggerFactory.getLogger(BufferedSocketChannel::class.java)
    }

    // buffers and encoder/decoder
    private val inBuffer = ByteBuffer.allocate(bufCapacity)
    private val outBuffer = ByteBuffer.allocate(bufCapacity)
    private val decoder = Charsets.UTF_8.newDecoder()
    private val encoder = Charsets.UTF_8.newEncoder()
    private val chars = CharBuffer.allocate(Math.max(CHAR_BUF_SIZE, bufCapacity*2))

    private var maxLine = MAX_LINE

    // auxiliary state for line retrieving logic
    private var startIndex = 0
    private var lastEol : Char = NULL_CHAR


    private fun isEol(c:Char) =
        c == '\n' || c == '\r'

    private fun isEolPair(c1:Char, c2: Char) =
        c1 != c2 && isEol(c1) && isEol(c2)

    private class LineStat(val size : Int, val termSize : Int)

    /**
     * return lineStat including total line size and line terminator size(possible uncompleted),
     * return EMPTY_STAT if there is no line in chars (CharBuffer)
     * Combinations allowed:
     *     CR, LF -> 2
     *     LF -> 1
     *     CR -> 1
     *     LF, CR -> 2
     *     UNCOMPLETED TERMINATOR -> 1
     */
    private fun tryFindLine() : LineStat {
        for(i in (startIndex until minOf(maxLine+startIndex,chars.position()))) {
            if (isEol(chars[i])) {
                return if (i < chars.position() - 1) {
                    if (isEolPair(chars[i], chars[i+1]))
                        LineStat(i+2, 2);
                    else
                        LineStat(i +1, 1)
                } else {
                    lastEol = chars[i]
                    LineStat(i +1, 1) // possible uncompleted
                }
            }
        }
        return EMPTY_STAT
    }


    /**
     * retrieving a new line from char buffer
     * discarding line terminator
     */
    private fun retrieveLineFromCharBuffer(lineStat: LineStat) : String {
        val termSize = lineStat.termSize
        val lineSize = lineStat.size - termSize
        val charArray = CharArray(lineSize)

        chars.flip() // put in read mode

        chars.get(charArray, 0, lineSize)

        repeat(termSize) {
            chars.get()
        }

        chars.compact() // back to write mode
        startIndex = 0
        return String(charArray, 0, lineSize )
    }

    /**
     *  Get more bytes from channel to buffer
     *  Discard possible eol pair coming in new packet
     */
    private suspend fun moreBytesToInputBuffer(): Boolean {
        var nBytes = fill(inBuffer)
        if (nBytes == -2)  nBytes = channel.suspendingReadLine(inBuffer)
        logger.info("read $nBytes bytes  from channel ${channel.localAddress}")
        inBuffer.flip()  // put in read mode
        if (nBytes > 0 && isEolPair(lastEol, Char(inBuffer[0].toInt()))) { // Discard possible Eol Pair
            inBuffer.get()
        }
        lastEol = NULL_CHAR
        return ( nBytes > 0)
    }

    /**
     * decode all possible bytes from input ByteBuffer (inBuffer)
     * to CharBuffer (chars)
     */
    private fun decodeBytesInInputBuffer() {
        startIndex = chars.position()
        decoder.decode(inBuffer, chars, false)
        inBuffer.compact() // back to write mode
    }

    private fun flushCharBuffer() : String? =
        if (chars.position() > 0)
            retrieveLineFromCharBuffer(LineStat(minOf(maxLine,chars.position()), 0))
        else
            null


    suspend fun readLine() : String? {
        while(true) {
            val lineStat = tryFindLine()
            if (lineStat !==  EMPTY_STAT) {
                return retrieveLineFromCharBuffer(lineStat)
            }
            else if (chars.position() >= maxLine) {
                return retrieveLineFromCharBuffer(LineStat(maxLine, 0))
            }
            if (!moreBytesToInputBuffer()) {
                return  flushCharBuffer()
            }
            decodeBytesInInputBuffer()
        }
    }

    suspend fun writeLine(str: String)  {
        logger.info("write '$str' to channel ${channel.localAddress}")
        outBuffer.put(encoder.encode(CharBuffer.wrap(str.toCharArray())))
        outBuffer.put(13)
        outBuffer.put(10)
        outBuffer.flip()
        channel.suspendingWriteLine(outBuffer)
        outBuffer.clear()
    }

    override fun close() {
        channel.shutdownOutput()
        channel.shutdownInput()
        channel.close()
    }
}