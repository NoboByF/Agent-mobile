package com.example.tunnel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure Kotlin xtaci/smux v1 wire-compatible stream multiplexer.
 *
 * 8-byte frame header:
 * [0]    ver (1)
 * [1]    cmd (0=SYN, 1=FIN, 2=PSH, 3=NOP)
 * [2..3] length (uint16 LE)
 * [4..7] sid    (uint32 LE; client uses odd IDs: 3, 5, 7...)
 */
object SmuxWire {
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 8
    const val CMD_SYN: Int = 0
    const val CMD_FIN: Int = 1
    const val CMD_PSH: Int = 2
    const val CMD_NOP: Int = 3
    const val MAX_FRAME_DATA: Int = 4096

    data class Frame(
        val version: Int,
        val cmd: Int,
        val streamId: Int,
        val data: ByteArray
    )

    fun encodeFrame(frame: Frame): ByteArray {
        val out = ByteArray(HEADER_SIZE + frame.data.size)
        out[0] = (frame.version and 0xFF).toByte()
        out[1] = (frame.cmd and 0xFF).toByte()
        KcpWire.putShortLE(out, 2, frame.data.size)
        KcpWire.putIntLE(out, 4, frame.streamId)
        if (frame.data.isNotEmpty()) {
            System.arraycopy(frame.data, 0, out, HEADER_SIZE, frame.data.size)
        }
        return out
    }

    fun readFrame(input: InputStream): Frame? {
        val header = ByteArray(HEADER_SIZE)
        var off = 0
        while (off < HEADER_SIZE) {
            val n = input.read(header, off, HEADER_SIZE - off)
            if (n < 0) return null
            off += n
        }
        val ver = header[0].toInt() and 0xFF
        val cmd = header[1].toInt() and 0xFF
        val len = KcpWire.getShortLE(header, 2)
        val sid = KcpWire.getIntLE(header, 4)
        val data = ByteArray(len)
        var dOff = 0
        while (dOff < len) {
            val n = input.read(data, dOff, len - dOff)
            if (n < 0) throw IOException("Truncated SMUX frame payload (expected $len bytes)")
            dOff += n
        }
        return Frame(ver, cmd, sid, data)
    }
}

class SmuxSession(
    private val transportIn: InputStream,
    private val transportOut: OutputStream
) {
    private val closed = AtomicBoolean(false)
    private val txLock = Any()
    private val nextStreamId = AtomicInteger(3) // xtaci/smux client starts at odd ID 3
    private val streams = ConcurrentHashMap<Int, SmuxStream>()
    private val lastActivityMs = AtomicLong(System.currentTimeMillis())

    private var readerThread: Thread? = null
    private var keepaliveThread: Thread? = null

    fun lastActivityTimestampMs(): Long = lastActivityMs.get()
    fun isClosed(): Boolean = closed.get()

    fun start() {
        readerThread = Thread({ readLoop() }, "smux-reader").apply {
            isDaemon = true
            start()
        }
        keepaliveThread = Thread({ keepaliveLoop() }, "smux-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    fun openStream(): SmuxStream {
        if (closed.get()) throw IOException("SMUX session closed")
        val sid = nextStreamId.getAndAdd(2)
        val stream = SmuxStream(sid, this)
        streams[sid] = stream
        writeFrame(SmuxWire.Frame(SmuxWire.VERSION, SmuxWire.CMD_SYN, sid, ByteArray(0)))
        return stream
    }

    internal fun writeFrame(frame: SmuxWire.Frame) {
        if (closed.get()) throw IOException("SMUX session closed")
        val bytes = SmuxWire.encodeFrame(frame)
        synchronized(txLock) {
            transportOut.write(bytes)
            transportOut.flush()
        }
    }

    internal fun removeStream(sid: Int) {
        streams.remove(sid)
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val frame = SmuxWire.readFrame(transportIn) ?: break
                lastActivityMs.set(System.currentTimeMillis())
                when (frame.cmd) {
                    SmuxWire.CMD_PSH -> {
                        streams[frame.streamId]?.deliverData(frame.data)
                    }

                    SmuxWire.CMD_FIN -> {
                        streams[frame.streamId]?.markRemoteFin()
                    }

                    SmuxWire.CMD_NOP -> {
                        // Keepalive ping from server
                    }

                    SmuxWire.CMD_SYN -> {
                        // Client does not accept inbound streams
                    }
                }
            }
        } catch (_: Exception) {
            // Transport closed or interrupted
        } finally {
            close()
        }
    }

    private fun keepaliveLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(10_000L)
            } catch (_: InterruptedException) {
                break
            }
            if (closed.get()) break
            runCatching {
                writeFrame(SmuxWire.Frame(SmuxWire.VERSION, SmuxWire.CMD_NOP, 0, ByteArray(0)))
            }.onFailure {
                close()
                break
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        streams.values.forEach { it.markRemoteFin() }
        streams.clear()
        readerThread?.interrupt()
        keepaliveThread?.interrupt()
    }
}

class SmuxStream(
    val streamId: Int,
    private val session: SmuxSession
) {
    private val rxLock = Object()
    private val rxBuf = ArrayDeque<Byte>()
    private val remoteClosed = AtomicBoolean(false)
    private val localClosed = AtomicBoolean(false)

    internal fun deliverData(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(rxLock) {
            for (b in bytes) rxBuf.addLast(b)
            rxLock.notifyAll()
        }
    }

    internal fun markRemoteFin() {
        remoteClosed.set(true)
        synchronized(rxLock) {
            rxLock.notifyAll()
        }
    }

    val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            synchronized(rxLock) {
                while (rxBuf.isEmpty()) {
                    if (remoteClosed.get() || localClosed.get() || session.isClosed()) return -1
                    try {
                        rxLock.wait(250L)
                    } catch (_: InterruptedException) {
                        return -1
                    }
                }
                var count = 0
                while (count < len && rxBuf.isNotEmpty()) {
                    b[off + count] = rxBuf.removeFirst()
                    count++
                }
                return count
            }
        }

        override fun close() {
            this@SmuxStream.close()
        }
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (localClosed.get() || session.isClosed()) throw IOException("SMUX stream closed")
            var pos = off
            val end = off + len
            while (pos < end) {
                val chunkLen = minOf(SmuxWire.MAX_FRAME_DATA, end - pos)
                val chunk = b.copyOfRange(pos, pos + chunkLen)
                session.writeFrame(SmuxWire.Frame(SmuxWire.VERSION, SmuxWire.CMD_PSH, streamId, chunk))
                pos += chunkLen
            }
        }

        override fun flush() = Unit

        override fun close() {
            this@SmuxStream.close()
        }
    }

    fun asSocket(): Socket = object : Socket() {
        override fun getInputStream(): InputStream = this@SmuxStream.inputStream
        override fun getOutputStream(): OutputStream = this@SmuxStream.outputStream
        override fun isConnected(): Boolean = !localClosed.get() && !session.isClosed()
        override fun isClosed(): Boolean = localClosed.get() || session.isClosed()
        override fun setSoTimeout(timeout: Int) = Unit
        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun close() {
            this@SmuxStream.close()
        }
    }

    fun close() {
        if (!localClosed.compareAndSet(false, true)) return
        runCatching {
            session.writeFrame(SmuxWire.Frame(SmuxWire.VERSION, SmuxWire.CMD_FIN, streamId, ByteArray(0)))
        }
        session.removeStream(streamId)
        synchronized(rxLock) {
            rxLock.notifyAll()
        }
    }
}
