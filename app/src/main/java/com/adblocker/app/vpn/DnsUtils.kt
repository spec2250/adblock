package com.adblocker.app.vpn

object DnsUtils {
    // Returns the first queried domain name (lowercase) or null.
    fun parseQueryDomain(data: ByteArray, len: Int): String? {
        if (len < 12) return null
        // header 12 bytes; question starts at 12
        var i = 12
        val sb = StringBuilder()
        while (i < len) {
            val l = data[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 == 0xC0) return null // compression not expected in question
            if (i + 1 + l > len) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append((data[i + k].toInt() and 0xFF).toChar())
            i += 1 + l
        }
        // QTYPE (2) + QCLASS (2)
        return if (sb.isNotEmpty()) sb.toString().lowercase() else null
    }

    // Returns QTYPE of first question (1=A, 28=AAAA, etc.)
    fun parseQueryType(data: ByteArray, len: Int): Int {
        // find end of question name
        var i = 12
        while (i < len) {
            val l = data[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 == 0xC0) { i += 2; break }
            i += 1 + l
        }
        if (i + 4 > len) return 0
        return ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
    }

    // Build an NXDOMAIN response for a query (copy ID/flags/question).
    fun buildNxDomain(query: ByteArray, len: Int): ByteArray {
        // Copy whole query then modify header flags and counts.
        val out = query.copyOf(len)
        // flags: QR=1, OPCODE=0, AA=0, TC=0, RD=copy, RA=1, RCODE=3 (NXDOMAIN)
        val rd = (query[2].toInt() and 0x01) // RD bit is bit0 of byte2? flags bytes: byte2 high: QR OPCODE AA TC RD; RD is bit0 of byte2
        out[2] = (0x80 or (rd and 0x01)).toByte() // QR=1, RD preserved, others 0
        out[3] = 0x83.toByte() // RA=1 (bit7 of byte3? actually RA is bit7 of byte3? flags2: RA Z AD CD RCODE; RA=0x80) + RCODE=3 => 0x83
        // ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out[6] = 0; out[7] = 0
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        return out
    }

    // Build a 0.0.0.0 A response (for blocked A queries) - simpler compatibility.
    // We'll instead use NXDOMAIN; keep helper for future.
}