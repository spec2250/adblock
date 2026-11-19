package com.adblocker.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.adblocker.app.BlocklistManager
import com.adblocker.app.MainActivity
import com.adblocker.app.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class AdBlockVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var running = false
    private var worker: Thread? = null

    companion object {
        const val ACTION_START = "com.adblocker.app.START"
        const val ACTION_STOP = "com.adblocker.app.STOP"
        const val CHANNEL_ID = "adblocker_vpn"
        var blockedCount: Long = 0L
        var isRunning: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        BlocklistManager.load(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "AdBlocker VPN", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setContentTitle("AdBlocker").setContentText("VPN active").setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun startVpn() {
        if (running) return
        val builder = Builder()
        builder.setSession("AdBlocker")
        builder.addAddress("10.0.0.2", 24)
        builder.addRoute("10.0.0.2", 32)
        builder.addDnsServer("10.0.0.2")
        builder.setMtu(1500)
        try {
            tun = builder.establish()
        } catch (_: Exception) {
            tun = null
            return
        }
        if (tun == null) return
        running = true
        isRunning = true
        startForeground(1, buildNotification())
        worker = Thread { runLoop() }
        worker?.start()
    }

    private fun stopVpn() {
        running = false
        isRunning = false
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        worker?.interrupt()
        worker = null
        stopForeground(true)
        stopSelf()
    }

    private fun runLoop() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try { input.read(buf) } catch (_: Exception) { -1 }
            if (n <= 0) break
            handlePacket(buf, n, output)
        }
        try { input.close() } catch (_: Exception) { }
        try { output.close() } catch (_: Exception) { }
    }

    private fun handlePacket(buf: ByteArray, n: Int, out: FileOutputStream) {
        if (n < 20) return
        val ver = (buf[0].toInt() shr 4) and 0x0F
        if (ver != 4) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        val proto = buf[9].toInt() and 0xFF
        if (proto != 17) return // UDP only
        val totalLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if (totalLen < ihl + 8) return
        val srcIp = ipBytes(buf, 12)
        val dstIp = ipBytes(buf, 16)
        val udpOff = ihl
        val srcPort = ((buf[udpOff].toInt() and 0xFF) shl 8) or (buf[udpOff + 1].toInt() and 0xFF)
        val dstPort = ((buf[udpOff + 2].toInt() and 0xFF) shl 8) or (buf[udpOff + 3].toInt() and 0xFF)
        val udpLen = ((buf[udpOff + 4].toInt() and 0xFF) shl 8) or (buf[udpOff + 5].toInt() and 0xFF)
        if (dstPort != 53) return
        val dnsOff = udpOff + 8
        val dnsLen = udpLen - 8
        if (dnsLen <= 0 || dnsOff + dnsLen > n) return
        val query = buf.copyOfRange(dnsOff, dnsOff + dnsLen)

        val domain = DnsUtils.parseQueryDomain(query, dnsLen)
        val qtype = DnsUtils.parseQueryType(query, dnsLen)

        val responseDns: ByteArray = if (domain != null && BlocklistManager.isBlocked(domain)) {
            blockedCount++
            DnsUtils.buildNxDomain(query, dnsLen)
        } else {
            // forward
            forwardQuery(query, dnsLen) ?: DnsUtils.buildNxDomain(query, dnsLen)
        }

        // build IP/UDP packet back: src=dstIp(original), dst=srcIp(original), srcPort=53, dstPort=srcPort
        val pkt = buildUdpPacket(dstIp, srcIp, 53, srcPort, responseDns)
        try { out.write(pkt); out.flush() } catch (_: Exception) { }
    }

    private fun ipBytes(b: ByteArray, off: Int): ByteArray =
        byteArrayOf(b[off], b[off + 1], b[off + 2], b[off + 3])

    private fun forwardQuery(query: ByteArray, len: Int): ByteArray? {
        return try {
            val sock = DatagramSocket()
            protect(sock)
            sock.soTimeout = 5000
            val upstream = InetSocketAddress("8.8.8.8", 53)
            sock.send(DatagramPacket(query, len, upstream))
            val rbuf = ByteArray(4096)
            val rp = DatagramPacket(rbuf, rbuf.size)
            sock.receive(rp)
            sock.close()
            rbuf.copyOf(rp.length)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildUdpPacket(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)
        // IP header
        pkt[0] = 0x45.toByte()
        pkt[1] = 0
        pkt[2] = (totalLen shr 8).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[4] = 0; pkt[5] = 0 // id
        pkt[6] = 0; pkt[7] = 0 // flags/frag
        pkt[8] = 64 // ttl
        pkt[9] = 17 // udp
        // checksum placeholder 10,11
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)
        // ip checksum
        val csum = ipChecksum(pkt, 0, 20)
        pkt[10] = (csum shr 8).toByte()
        pkt[11] = (csum and 0xFF).toByte()
        // UDP
        val u = 20
        pkt[u] = (srcPort shr 8).toByte()
        pkt[u + 1] = (srcPort and 0xFF).toByte()
        pkt[u + 2] = (dstPort shr 8).toByte()
        pkt[u + 3] = (dstPort and 0xFF).toByte()
        pkt[u + 4] = (udpLen shr 8).toByte()
        pkt[u + 5] = (udpLen and 0xFF).toByte()
        pkt[u + 6] = 0; pkt[u + 7] = 0 // checksum 0 (allowed IPv4)
        System.arraycopy(payload, 0, pkt, u + 8, payload.size)
        return pkt
    }

    private fun ipChecksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len - 1) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < off + len) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv()) and 0xFFFF
    }
}