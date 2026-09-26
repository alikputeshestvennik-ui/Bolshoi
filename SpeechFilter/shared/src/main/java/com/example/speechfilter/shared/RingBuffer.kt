package com.example.speechfilter.shared

class ShortRingBuffer(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var write = 0
    private var size = 0

    @Synchronized fun add(input: ShortArray) {
        for (v in input) {
            data[write] = v
            write = (write + 1) % capacity
            if (size < capacity) size++
        }
    }

    @Synchronized fun snapshot(): ShortArray {
        val out = ShortArray(size)
        val start = (write - size + capacity) % capacity
        for (i in 0 until size) out[i] = data[(start + i) % capacity]
        return out
    }

    @Synchronized fun clear() { write = 0; size = 0 }
}
