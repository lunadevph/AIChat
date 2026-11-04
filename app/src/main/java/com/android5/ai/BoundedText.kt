package com.android5.ai

import java.io.Reader

internal object BoundedText {
    fun read(reader: Reader, limit: Int): String {
        require(limit >= 0)
        val buffer = CharArray(limit)
        var count = 0
        while (count < buffer.size) {
            val read = reader.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) {
                val next = reader.read()
                if (next < 0) break
                buffer[count++] = next.toChar()
            } else count += read
        }
        return String(buffer, 0, count)
    }
}
