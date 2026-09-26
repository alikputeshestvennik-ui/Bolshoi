package com.example.speechfilter.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioPacket {
    const val MAGIC = 0x53465031 // "SFP1"
    const val HEADER = 12

    fun encode(sequence: Int, pcm: ShortArray): ByteArray {
        val b = ByteBuffer.allocate(HEADER + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC)
        b.putInt(sequence)
        b.putInt(pcm.size * 2)
        for (s in pcm) b.putShort(s)
        return b.array()
    }

    class Parser(private val onPacket: (Int, ShortArray) -> Unit) {
        private var buf = ByteArray(0)
        fun push(bytes: ByteArray) {
            buf += bytes
            while (true) {
                if (buf.size < HEADER) return
                val h = ByteBuffer.wrap(buf, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
                if (h.int != MAGIC) { buf = buf.copyOfRange(1, buf.size); continue }
                val seq = h.int
                val len = h.int
                if (len < 0 || len > 1_000_000) { buf = buf.copyOfRange(4, buf.size); continue }
                if (buf.size < HEADER + len) return
                val pcm = ShortArray(len / 2)
                val p = ByteBuffer.wrap(buf, HEADER, len).order(ByteOrder.LITTLE_ENDIAN)
                for (i in pcm.indices) pcm[i] = p.short
                onPacket(seq, pcm)
                buf = buf.copyOfRange(HEADER + len, buf.size)
            }
        }
    }
}
