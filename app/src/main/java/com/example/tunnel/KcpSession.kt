package com.example.tunnel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure Kotlin xtaci/kcp-go wire-compatible KCP reliable stream session.
 *
 * 24-byte Little-Endian segment header:
 * [0..3]  conv (uint32 LE)
 * [4]     cmd  (uint8: 81=PUSH, 82=ACK, 83=WASK, 84=WINS)
 * [5]     frg  (uint8: 0 in stream mode)
 * [6..7]  wnd  (uint16 LE)
 * [8..11] ts   (uint32 LE)
 * [12..15] sn  (uint32 LE)
 * [16..19] una (uint32 LE)
 * [20..23] len (uint32 LE)
 */
object KcpWire {
    const val HEADER_SIZE = 24
    const val CMD_PUSH: Int = 81
    const val CMD_ACK: Int = 82
    const val CMD_WASK: Int = 83
    const val CMD_WINS: Int = 84

    data class Segment(
        val conv: Int,
        val cmd: Int,
        val frg: Int,
        val wnd: Int,
        val ts: Int,
        val sn: Int,
        val una: Int,
        val data: ByteArray
    )

    fun encodeSegment(seg: Segment): ByteArray {
        val out = ByteArray(HEADER_SIZE + seg.data.size)
        putIntLE(out, 0, seg.conv)
        out[4] = (seg.cmd and 0xFF).toByte()
        out[5] = (seg.frg and 0xFF).toByte()
        putShortLE(out, 6, seg.wnd)
        putIntLE(out, 8, seg.ts)
        putIntLE(out, 12, seg.sn)
        putIntLE(out, 16, seg.una)
        putIntLE(out, 20, seg.data.size)
        if (seg.data.isNotEmpty()) {
            System.arraycopy(seg.data, 0, out, HEADER_SIZE, seg.data.size)
        }
        return out
    }

    fun decodeSegments(packet: ByteArray): List<Segment> {
        val list = mutableListOf<Segment>()
        var offset = 0
        while (offset + HEADER_SIZE <= packet.size) {
            val conv = getIntLE(packet, offset)
            val cmd = packet[offset + 4].toInt() and 0xFF
            val frg = packet[offset + 5].toInt() and 0xFF
            val wnd = getShortLE(packet, offset + 6)
            val ts = getIntLE(packet, offset + 8)
            val sn = getIntLE(packet, offset + 12)
            val una = getIntLE(packet, offset + 16)
            val len = getIntLE(packet, offset + 20)
            offset += HEADER_SIZE
            if (len < 0 || offset + len > packet.size) break
            val payload = if (len > 0) packet.copyOfRange(offset, offset + len) else ByteArray(0)
            offset += len
            list.add(Segment(conv, cmd, frg, wnd, ts, sn, una, payload))
        }
        return list
    }

    fun putShortLE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun getShortLE(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
    }

    fun putIntLE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun getIntLE(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
    }
}

class KcpSession(
    private val mtu: Int,
    private val sendPacket: (ByteArray) -> Unit,
    private val pollPacket: (Long) -> ByteArray?,
    val conv: Int = generateNonZeroConv(),
    private val windowSize: Int = 256
) {
    private val mss: Int = (mtu - KcpWire.HEADER_SIZE).coerceAtLeast(24)
    private val closed = AtomicBoolean(false)
    private val lock = Object()

    private class TxEntry(
        val sn: Int,
        val data: ByteArray,
        var ts: Int = 0,
        var resendTs: Long = 0L,
        var rto: Long = 350L,
        var fastAck: Int = 0,
        var xmit: Int = 0
    )

    private var sndUna: Int = 0
    private var sndNxt: Int = 0
    private var rcvNxt: Int = 0
    private var rmtWnd: Int = windowSize

    private var rxSrtt: Long = 0L
    private var rxRttval: Long = 0L
    private var rxRto: Long = 400L

    private val sndQueue = ArrayDeque<ByteArray>()
    private val sndBuf = TreeMap<Int, TxEntry>()
    private val rcvBuf = TreeMap<Int, ByteArray>()
    private val ackList = ArrayDeque<Pair<Int, Int>>() // (sn, ts)
    private val rxStream = ArrayDeque<Byte>()

    private var startEpochMs: Long = System.currentTimeMillis()
    private var tickThread: Thread? = null
    private var recvThread: Thread? = null

    fun start() {
        startEpochMs = System.currentTimeMillis()
        recvThread = Thread({ recvLoop() }, "kcp-recv-$conv").apply {
            isDaemon = true
            start()
        }
        tickThread = Thread({ tickLoop() }, "kcp-tick-$conv").apply {
            isDaemon = true
            start()
        }
    }

    private fun currentMs(): Int = ((System.currentTimeMillis() - startEpochMs) and 0x7FFFFFFFL).toInt()

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (length <= 0) return
        synchronized(lock) {
            if (closed.get()) throw IOException("KCP session closed")
            var pos = offset
            val end = offset + length
            while (pos < end) {
                val chunkLen = minOf(mss, end - pos)
                sndQueue.addLast(data.copyOfRange(pos, pos + chunkLen))
                pos += chunkLen
            }
            flushLocked()
        }
    }

    fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Long = 20_000L): Int {
        if (length <= 0) return 0
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (rxStream.isEmpty()) {
                if (closed.get()) return -1
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0L) throw IOException("KCP read timed out")
                lock.wait(minOf(rem, 250L))
            }
            var count = 0
            while (count < length && rxStream.isNotEmpty()) {
                dest[offset + count] = rxStream.removeFirst()
                count++
            }
            return count
        }
    }

    fun readFully(length: Int, timeoutMs: Long = 20_000L): ByteArray {
        val out = ByteArray(length)
        var readSoFar = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (readSoFar < length) {
            val rem = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val n = read(out, readSoFar, length - readSoFar, rem)
            if (n < 0) throw IOException("EOF on KCP stream")
            readSoFar += n
        }
        return out
    }

    fun inputPacket(packet: ByteArray) {
        val segments = KcpWire.decodeSegments(packet)
        if (segments.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val nowTs = currentMs()

        synchronized(lock) {
            if (closed.get()) return
            var maxAck = -1
            var hasAck = false

            for (seg in segments) {
                if (seg.conv != conv) continue
                rmtWnd = seg.wnd.coerceAtLeast(1)
                parseUnaLocked(seg.una)

                when (seg.cmd) {
                    KcpWire.CMD_ACK -> {
                        val rtt = (nowTs - seg.ts).toLong()
                        if (rtt in 0L..10_000L) {
                            updateRttLocked(rtt)
                        }
                        sndBuf.remove(seg.sn)
                        updateSndUnaLocked()
                        if (!hasAck || seg.sn > maxAck) {
                            maxAck = seg.sn
                            hasAck = true
                        }
                    }

                    KcpWire.CMD_PUSH -> {
                        if (seg.sn < rcvNxt + windowSize) {
                            ackList.addLast(seg.sn to seg.ts)
                            if (seg.sn >= rcvNxt && !rcvBuf.containsKey(seg.sn)) {
                                rcvBuf[seg.sn] = seg.data
                            }
                            drainOrderedRcvBufLocked()
                        }
                    }

                    KcpWire.CMD_WASK -> {
                        // Reply with window size immediately
                        val wins = KcpWire.encodeSegment(
                            KcpWire.Segment(conv, KcpWire.CMD_WINS, 0, availableRcvWnd(), nowTs, 0, rcvNxt, ByteArray(0))
                        )
                        sendPacket(wins)
                    }

                    KcpWire.CMD_WINS -> {
                        // Remote window already updated above
                    }
                }
            }

            if (hasAck) {
                for (entry in sndBuf.values) {
                    if (entry.sn < maxAck) {
                        entry.fastAck++
                    }
                }
            }
            flushLocked(nowMs)
        }
    }

    private fun parseUnaLocked(una: Int) {
        val it = sndBuf.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key < una) it.remove() else break
        }
        updateSndUnaLocked()
    }

    private fun updateSndUnaLocked() {
        sndUna = sndBuf.firstKeyOrNull() ?: sndNxt
    }

    private fun TreeMap<Int, TxEntry>.firstKeyOrNull(): Int? = if (isEmpty()) null else firstKey()

    private fun updateRttLocked(rtt: Long) {
        if (rxSrtt == 0L) {
            rxSrtt = rtt
            rxRttval = rtt / 2L
        } else {
            val delta = kotlin.math.abs(rtt - rxSrtt)
            rxRttval = (3 * rxRttval + delta) / 4
            rxSrtt = (7 * rxSrtt + rtt) / 8
            if (rxSrtt < 1L) rxSrtt = 1L
        }
        rxRto = (rxSrtt + maxOf(30L, 4 * rxRttval)).coerceIn(220L, 4000L)
    }

    private fun availableRcvWnd(): Int = (windowSize - rcvBuf.size).coerceAtLeast(1)

    private fun drainOrderedRcvBufLocked() {
        var delivered = false
        while (rcvBuf.containsKey(rcvNxt)) {
            val bytes = rcvBuf.remove(rcvNxt) ?: break
            rcvNxt++
            for (b in bytes) rxStream.addLast(b)
            delivered = true
        }
        if (delivered) {
            lock.notifyAll()
        }
    }

    private fun flushLocked(nowMs: Long = System.currentTimeMillis()) {
        if (closed.get()) return
        val nowTs = currentMs()
        val wnd = availableRcvWnd()

        // Move pending chunks from sndQueue into sndBuf up to cwnd
        val maxInFlight = minOf(windowSize, rmtWnd).coerceAtLeast(16)
        while (sndQueue.isNotEmpty() && sndBuf.size < maxInFlight) {
            val data = sndQueue.removeFirst()
            val sn = sndNxt++
            sndBuf[sn] = TxEntry(sn = sn, data = data, rto = rxRto)
        }

        val packetBuf = ByteArrayOutputStream(mtu)
        fun emitBuffered() {
            if (packetBuf.size() > 0) {
                sendPacket(packetBuf.toByteArray())
                packetBuf.reset()
            }
        }

        // 1. Flush pending ACKs
        while (ackList.isNotEmpty()) {
            val (ackSn, ackTs) = ackList.removeFirst()
            if (packetBuf.size() + KcpWire.HEADER_SIZE > mtu) {
                emitBuffered()
            }
            packetBuf.write(
                KcpWire.encodeSegment(
                    KcpWire.Segment(conv, KcpWire.CMD_ACK, 0, wnd, ackTs, ackSn, rcvNxt, ByteArray(0))
                )
            )
        }

        // 2. Flush initial and retransmitted PUSH segments
        for (entry in sndBuf.values) {
            val needSend = when {
                entry.xmit == 0 -> {
                    entry.xmit = 1
                    entry.rto = rxRto
                    entry.resendTs = nowMs + entry.rto
                    true
                }

                nowMs >= entry.resendTs -> {
                    entry.xmit++
                    entry.rto = (entry.rto + (entry.rto / 2).coerceAtLeast(150L)).coerceAtMost(4500L)
                    entry.resendTs = nowMs + entry.rto
                    true
                }

                entry.fastAck >= 2 -> {
                    entry.xmit++
                    entry.fastAck = 0
                    entry.resendTs = nowMs + entry.rto
                    true
                }

                else -> false
            }

            if (needSend) {
                entry.ts = nowTs
                val encoded = KcpWire.encodeSegment(
                    KcpWire.Segment(conv, KcpWire.CMD_PUSH, 0, wnd, entry.ts, entry.sn, rcvNxt, entry.data)
                )
                if (packetBuf.size() + encoded.size > mtu) {
                    emitBuffered()
                }
                packetBuf.write(encoded)
            }
        }

        emitBuffered()
    }

    private fun recvLoop() {
        while (!closed.get()) {
            val pkt = pollPacket(100L) ?: continue
            if (closed.get()) break
            inputPacket(pkt)
        }
    }

    private fun tickLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(30L)
            } catch (_: InterruptedException) {
                break
            }
            if (closed.get()) break
            synchronized(lock) {
                flushLocked()
            }
        }
    }

    fun asInputStream(readTimeoutMs: Long = 30_000L): InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            val n = this@KcpSession.read(b, 0, 1, readTimeoutMs)
            return if (n <= 0) -1 else (b[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return this@KcpSession.read(b, off, len, readTimeoutMs)
        }
    }

    fun asOutputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            this@KcpSession.write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            this@KcpSession.write(b, off, len)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            lock.notifyAll()
        }
        recvThread?.interrupt()
        tickThread?.interrupt()
    }

    companion object {
        fun generateNonZeroConv(): Int {
            val rnd = SecureRandom()
            var c = 0
            while (c == 0) {
                c = rnd.nextInt()
            }
            return c
        }
    }
}
