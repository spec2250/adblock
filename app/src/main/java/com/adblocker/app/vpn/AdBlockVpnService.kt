package com.adblocker.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.adblocker.app.BlocklistManager
import com.adblocker.app.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Local VPN that blocks ads by intercepting DNS, while transparently
 * forwarding ALL other traffic (TCP, UDP, ICMP) to the real network.
 * Non-DNS packets must never be dropped, otherwise the phone loses internet.
 */
class AdBlockVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var running = false
    private var worker: Thread? = null
    private var cleaner: Thread? = null
    private var output: FileOutputStream? = null
    private val writeLock = Any()

    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private val tcpFlows = ConcurrentHashMap<String, TcpFlow>()

    companion object {
        const val ACTION_START = "com.adblocker.app.START"
        const val ACTION_STOP = "com.adblocker.app.STOP"
        const val CHANNEL_ID = "adblocker_vpn"
        const val VPN_ADDR = "10.0.0.2"
        const val VPN_DNS = "10.0.0.3"
        private val EMPTY = ByteArray(0)
        private val isn = AtomicLong(System.currentTimeMillis() and 0x7FFFFFFFL)

        @Volatile var blockedCount: Long = 0L
        @Volatile var isRunning: Boolean = false
        @Volatile var lastBlockedDomain: String = ""
        @Volatile var startedAt: Long = 0L
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "AdBlocker VPN", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setContentTitle("AdBlocker")
            .setContentText(if (running) "Protection active" else "VPN active")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun startVpn() {
        if (running) return
        val builder = Builder()
        builder.setSession("AdBlocker")
        builder.addAddress(VPN_ADDR, 24)
        builder.addDnsServer(VPN_DNS)
        builder.addRoute(VPN_DNS, 32)
        // Only DNS is pulled into the VPN. Everything else keeps using the real
        // network (split tunnel) so normal internet traffic is never touched.
        builder.addRoute("8.8.8.8", 32)
        builder.addRoute("8.8.4.4", 32)
        builder.addRoute("1.1.1.1", 32)
        builder.addRoute("1.0.0.1", 32)
        builder.addRoute("9.9.9.9", 32)
        // Let IPv6 fall through to the real network instead of being blocked.
        builder.allowFamily(OsConstants.AF_INET6)
        builder.setMtu(1500)
        // CRITICAL: establish() is non-blocking by default, which makes read()
        // return immediately and would kill the worker loop on the first idle
        // moment. Blocking mode keeps the loop alive.
        builder.setBlocking(true)
        try {
            tun = builder.establish()
        } catch (_: Exception) {
            tun = null
            return
        }
        if (tun == null) return

        running = true
        isRunning = true
        startedAt = System.currentTimeMillis()
        startForeground(1, buildNotification())
        worker = Thread { runLoop() }
        worker?.start()
        cleaner = Thread { cleanLoop() }
        cleaner?.start()
    }

    private fun stopVpn() {
        running = false
        isRunning = false
        for (f in udpFlows.values) f.close()
        udpFlows.clear()
        for (f in tcpFlows.values) f.close()
        tcpFlows.clear()
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        output = null
        worker?.interrupt()
        worker = null
        cleaner?.interrupt()
        cleaner = null
        stopForeground(true)
        stopSelf()
    }

    private fun cleanLoop() {
        while (running) {
            try { Thread.sleep(20_000L) } catch (_: InterruptedException) { running = false }
            val now = System.currentTimeMillis()
            val deadUdp = ArrayList<String>()
            for (e in udpFlows) if (now - e.value.lastUsed > 60_000L) deadUdp.add(e.key)
            for (k in deadUdp) udpFlows.remove(k)?.close()

            val deadTcp = ArrayList<String>()
            for (e in tcpFlows) if (now - e.value.lastUsed > 180_000L) deadTcp.add(e.key)
            for (k in deadTcp) tcpFlows.remove(k)?.close()
        }
    }

    private fun runLoop() {
        val fd = tun ?: return
        if (!BlocklistManager.isLoaded()) BlocklistManager.load(this)
        val input = FileInputStream(fd.fileDescriptor)
        output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try { input.read(buf) } catch (_: Exception) { -1 }
            if (n <= 0) break
            try { handlePacket(buf, n) } catch (_: Exception) { }
        }
        try { input.close() } catch (_: Exception) { }
        try { output?.close() } catch (_: Exception) { }
        output = null
    }

    /** Inject a packet back into the VPN (reply coming from the network). */
    private fun inject(pkt: ByteArray) {
        val o = output ?: return
        synchronized(writeLock) {
            try { o.write(pkt); o.flush() } catch (_: Exception) { }
        }
    }

    private fun handlePacket(buf: ByteArray, n: Int) {
        if (n < 20) return
        if (((buf[0].toInt() shr 4) and 0x0F) != 4) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > n) return
        val totalLen = u16(buf, 2)
        if (totalLen < ihl || totalLen > n) return
        // Reassembled fragments only; fragmented packets are ignored.
        val frag = u16(buf, 6)
        if ((frag and 0x2000) != 0 || (frag and 0x1FFF) != 0) return

        val proto = buf[9].toInt() and 0xFF
        val srcIp = ipBytes(buf, 12)
        val dstIp = ipBytes(buf, 16)
        when (proto) {
            17 -> handleUdp(buf, totalLen, ihl, srcIp, dstIp)
            6 -> handleTcp(buf, totalLen, ihl, srcIp, dstIp)
            1 -> handleIcmp(buf, totalLen, ihl, srcIp, dstIp)
        }
    }

    // ---------------- DNS ----------------

    private fun handleUdp(
        buf: ByteArray, totalLen: Int, ihl: Int, srcIp: ByteArray, dstIp: ByteArray
    ) {
        if (totalLen < ihl + 8) return
        val u = ihl
        val srcPort = u16(buf, u)
        val dstPort = u16(buf, u + 2)
        val udpLen = u16(buf, u + 4)
        if (udpLen < 8) return
        val payloadOff = u + 8
        val payloadLen = minOf(udpLen - 8, totalLen - payloadOff)
        if (payloadLen <= 0) return
        val payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen)

        if (dstPort == 53) {
            val domain = DnsUtils.parseQueryDomain(payload, payloadLen)
            val response = if (domain != null && BlocklistManager.isBlocked(domain)) {
                blockedCount = blockedCount + 1
                lastBlockedDomain = domain
                dnsResponse(payload, payloadLen, 3)
            } else {
                forwardQuery(payload, payloadLen) ?: dnsResponse(payload, payloadLen, 2)
            }
            inject(buildUdpPacket(dstIp, srcIp, 53, srcPort, response))
            return
        }

        // Every other UDP flow (QUIC, games, calls...) must be relayed.
        val key = flowKey(srcIp, srcPort, dstIp, dstPort)
        var flow = udpFlows[key]
        if (flow == null) {
            flow = try { UdpFlow(srcIp, srcPort, dstIp, dstPort) } catch (_: Exception) { null }
            if (flow == null) return
            udpFlows[key] = flow
        }
        flow.touch()
        flow.send(payload)
    }

    private fun dnsResponse(query: ByteArray, len: Int, rcode: Int): ByteArray {
        if (len < 12) return query.copyOf(len)
        val out = query.copyOf(len)
        val rd = query[2].toInt() and 0x01
        out[2] = (0x80 or rd).toByte()
        out[3] = (0x80 or (rcode and 0x0F)).toByte()
        out[6] = 0; out[7] = 0
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        return out
    }

    private fun upstreamDns(): List<String> {
        val list = ArrayList<String>()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (net in cm.allNetworks) {
                val lp = cm.getLinkProperties(net) ?: continue
                val servers = lp.dnsServers ?: continue
                // Skip our own VPN network, otherwise we would forward queries
                // straight back into ourselves and time out on every lookup.
                var isOurs = false
                for (d in servers) if (d.hostAddress == VPN_DNS) isOurs = true
                if (isOurs) continue
                for (d in servers) {
                    val h = d.hostAddress ?: continue
                    if (h.indexOf(':') < 0) list.add(h)   // IPv4 only
                }
            }
        } catch (_: Exception) { }
        list.addAll(listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "208.67.222.222"))
        return list.distinct()
    }

    private fun forwardQuery(query: ByteArray, len: Int): ByteArray? {
        val qid = u16(query, 0)
        for (upstream in upstreamDns()) {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                protect(sock)
                sock.soTimeout = 1500
                val addr = InetAddress.getByName(upstream)
                sock.send(DatagramPacket(query, len, addr, 53))
                val rb = ByteArray(8192)
                val rp = DatagramPacket(rb, rb.size)
                sock.receive(rp)
                if (rp.length >= 12 && u16(rb, 0) == qid) return rb.copyOf(rp.length)
            } catch (_: Exception) {
            } finally {
                try { sock?.close() } catch (_: Exception) { }
            }
        }
        return null
    }

    // ---------------- UDP relay ----------------

    private inner class UdpFlow(
        val srcIp: ByteArray, val srcPort: Int, val dstIp: ByteArray, val dstPort: Int
    ) {
        @Volatile var lastUsed = System.currentTimeMillis()
        @Volatile private var stopped = false
        private val sock = DatagramSocket()

        init {
            protect(sock)
            sock.connect(InetAddress.getByAddress(dstIp), dstPort)
            Thread {
                val rb = ByteArray(8192)
                while (!stopped && running) {
                    try {
                        val rp = DatagramPacket(rb, rb.size)
                        sock.receive(rp)
                        if (rp.length > 0) {
                            inject(buildUdpPacket(dstIp, srcIp, dstPort, srcPort,
                                rb.copyOfRange(0, rp.length)))
                        }
                    } catch (_: Exception) {
                        if (stopped || !running) stopped = true
                    }
                }
            }.start()
        }

        fun touch() { lastUsed = System.currentTimeMillis() }
        fun send(data: ByteArray) {
            try { sock.send(DatagramPacket(data, data.size)) } catch (_: Exception) { }
        }
        fun close() {
            stopped = true
            try { sock.close() } catch (_: Exception) { }
        }
    }

    // ---------------- TCP relay ----------------

    private fun handleTcp(
        buf: ByteArray, totalLen: Int, ihl: Int, srcIp: ByteArray, dstIp: ByteArray
    ) {
        val t = ihl
        if (totalLen < t + 20) return
        val srcPort = u16(buf, t)
        val dstPort = u16(buf, t + 2)
        val seq = u32(buf, t + 4)
        val dataOff = ((buf[t + 12].toInt() and 0xFF) shr 4) * 4
        if (dataOff < 20) return
        val flags = buf[t + 13].toInt() and 0xFF
        val payloadOff = t + dataOff
        if (payloadOff > totalLen) return
        val payloadLen = totalLen - payloadOff
        val key = flowKey(srcIp, srcPort, dstIp, dstPort)

        if ((flags and 0x04) != 0) {
            tcpFlows.remove(key)?.close()
            return
        }

        var flow = tcpFlows[key]
        if (flow == null) {
            if ((flags and 0x02) == 0) {
                inject(buildTcpPacket(dstIp, srcIp, dstPort, srcPort, 0L, 0L, 0x04, EMPTY))
                return
            }
            flow = TcpFlow(srcIp, srcPort, dstIp, dstPort, seq)
            tcpFlows[key] = flow
            inject(buildTcpPacket(dstIp, srcIp, dstPort, srcPort,
                flow.serverIsn, seq + 1L, 0x12, EMPTY))
            flow.startConnect()
            return
        }

        flow.touch()
        if ((flags and 0x01) != 0) {
            if (payloadLen > 0) flow.offer(buf.copyOfRange(payloadOff, payloadOff + payloadLen), seq)
            flow.clientFin = true
            flow.sendAck()
            return
        }
        if (payloadLen > 0) flow.offer(buf.copyOfRange(payloadOff, payloadOff + payloadLen), seq)
    }

    private inner class TcpFlow(
        val srcIp: ByteArray, val srcPort: Int, val dstIp: ByteArray, val dstPort: Int,
        clientIsn: Long
    ) {
        val serverIsn: Long = nextIsn()
        @Volatile var clientNext: Long = clientIsn + 1L
        @Volatile var serverSeq: Long = serverIsn + 1L
        @Volatile var clientFin = false
        @Volatile var closed = false
        @Volatile var lastUsed = System.currentTimeMillis()
        private var finSent = false
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var socket: Socket? = null

        fun touch() { lastUsed = System.currentTimeMillis() }

        fun startConnect() {
            Thread {
                var s: Socket? = null
                var failed = true
                try {
                    s = Socket()
                    protect(s)
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 10_000)
                    failed = false
                    val sock = s
                    socket = sock
                    Thread { writerLoop(sock) }.start()
                    readerLoop(sock)
                } catch (_: Exception) {
                } finally {
                    try { s?.close() } catch (_: Exception) { }
                    if (failed && !closed && running) {
                        inject(buildTcpPacket(dstIp, srcIp, dstPort, srcPort, 0L, 0L, 0x04, EMPTY))
                    }
                    close()
                    tcpFlows.remove(flowKey(srcIp, srcPort, dstIp, dstPort), this)
                }
            }.start()
        }

        private fun writerLoop(s: Socket) {
            try {
                while (!closed && running) {
                    val data = queue.poll(300L, TimeUnit.MILLISECONDS)
                    if (data != null) {
                        s.getOutputStream().write(data)
                        s.getOutputStream().flush()
                    } else if (clientFin) {
                        try { s.shutdownOutput() } catch (_: Exception) { }
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }

        private fun readerLoop(s: Socket) {
            val rb = ByteArray(1400)
            try {
                while (!closed && running) {
                    val n = s.getInputStream().read(rb)
                    if (n <= 0) break
                    var off = 0
                    while (off < n) {
                        val len = minOf(1400, n - off)
                        val data = rb.copyOfRange(off, off + len)
                        var sq = 0L
                        var ak = 0L
                        synchronized(this) {
                            sq = serverSeq
                            ak = clientNext
                            serverSeq += len.toLong()
                        }
                        inject(buildTcpPacket(dstIp, srcIp, dstPort, srcPort, sq, ak, 0x18, data))
                        off += len
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (!closed && running) sendFin()
            }
        }

        private fun sendFin() {
            if (finSent || closed) return
            synchronized(this) {
                if (finSent) return
                finSent = true
                inject(buildTcpPacket(dstIp, srcIp, dstPort, srcPort,
                    serverSeq, clientNext, 0x11, EMPTY))
                serverSeq += 1L
            }
        }

        fun offer(data: ByteArray, seq: Long) {
            synchronized(this) {
                if (seq == clientNext && queue.size < 1024) {
                    clientNext += data.size.toLong()
                    queue.offer(data)
                }
                // Always re-advertise the highest in-order byte we consumed, so a
                // retransmission or a gap makes the client resend what we miss.
                inject(buildTcpPacket(dstIp, srcIp, dstPort, srcPort,
                    serverSeq, clientNext, 0x10, EMPTY))
            }
        }

        fun sendAck() {
            synchronized(this) {
                inject(buildTcpPacket(dstIp, srcIp, dstPort, srcPort, serverSeq, clientNext, 0x10, EMPTY))
            }
        }

        fun close() {
            closed = true
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    private fun nextIsn(): Long = isn.addAndGet(65537L) and 0x7FFFFFFFL

    // ---------------- ICMP ----------------

    private fun handleIcmp(
        buf: ByteArray, totalLen: Int, ihl: Int, srcIp: ByteArray, dstIp: ByteArray
    ) {
        if (totalLen < ihl + 8) return
        if ((buf[ihl].toInt() and 0xFF) != 8) return
        val len = totalLen - ihl
        if (len > 1400) return
        val echo = buf.copyOfRange(ihl, totalLen)
        echo[0] = 0
        put16(echo, 2, 0)
        put16(echo, 2, ipChecksum(echo, 0, len))
        inject(buildIpPacket(dstIp, srcIp, 1, echo))
    }

    // ---------------- Packet builders ----------------

    private fun buildIpPacket(srcIp: ByteArray, dstIp: ByteArray, proto: Int, payload: ByteArray): ByteArray {
        val totalLen = 20 + payload.size
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x45.toByte()
        put16(pkt, 2, totalLen)
        pkt[8] = 64
        pkt[9] = proto.toByte()
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)
        put16(pkt, 10, ipChecksum(pkt, 0, 20))
        System.arraycopy(payload, 0, pkt, 20, payload.size)
        return pkt
    }

    private fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val udp = ByteArray(8 + payload.size)
        put16(udp, 0, srcPort)
        put16(udp, 2, dstPort)
        put16(udp, 4, udp.size)
        put16(udp, 6, 0)
        System.arraycopy(payload, 0, udp, 8, payload.size)
        return buildIpPacket(srcIp, dstIp, 17, udp)
    }

    private fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, payload: ByteArray
    ): ByteArray {
        val seg = ByteArray(20 + payload.size)
        put16(seg, 0, srcPort)
        put16(seg, 2, dstPort)
        put32(seg, 4, seq)
        put32(seg, 8, ack)
        seg[12] = 0x50.toByte()
        seg[13] = flags.toByte()
        put16(seg, 14, 65535)
        System.arraycopy(payload, 0, seg, 20, payload.size)
        put16(seg, 16, tcpChecksum(srcIp, dstIp, seg, seg.size))
        return buildIpPacket(srcIp, dstIp, 6, seg)
    }

    private fun ipBytes(b: ByteArray, off: Int): ByteArray =
        byteArrayOf(b[off], b[off + 1], b[off + 2], b[off + 3])

    private fun flowKey(a: ByteArray, ap: Int, b: ByteArray, bp: Int): String =
        "${ipStr(a)}:$ap>${ipStr(b)}:$bp"

    private fun ipStr(a: ByteArray): String =
        "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}." +
            "${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}"

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFFL) shl 24) or
            ((b[off + 1].toLong() and 0xFFL) shl 16) or
            ((b[off + 2].toLong() and 0xFFL) shl 8) or
            (b[off + 3].toLong() and 0xFFL)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v shr 24) and 0xFFL).toByte()
        b[off + 1] = ((v shr 16) and 0xFFL).toByte()
        b[off + 2] = ((v shr 8) and 0xFFL).toByte()
        b[off + 3] = (v and 0xFFL).toByte()
    }

    private fun ipChecksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += (((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < off + len) sum += ((b[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFFL) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun tcpChecksum(src: ByteArray, dst: ByteArray, seg: ByteArray, len: Int): Int {
        var sum = 0L
        sum += ((src[0].toLong() and 0xFFL) shl 24) or ((src[1].toLong() and 0xFFL) shl 16) or
            ((src[2].toLong() and 0xFFL) shl 8) or (src[3].toLong() and 0xFFL)
        sum += ((dst[0].toLong() and 0xFFL) shl 24) or ((dst[1].toLong() and 0xFFL) shl 16) or
            ((dst[2].toLong() and 0xFFL) shl 8) or (dst[3].toLong() and 0xFFL)
        sum += 6L
        sum += len.toLong()
        var i = 0
        while (i < len - 1) {
            sum += (((seg[i].toInt() and 0xFF) shl 8) or (seg[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < len) sum += ((seg[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFFL) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }
}
