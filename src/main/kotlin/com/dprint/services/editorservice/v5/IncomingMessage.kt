package com.dprint.services.editorservice.v5

import java.nio.ByteBuffer

private const val U32_BYTE_SIZE = 4

class IncomingMessage(private val buffer: ByteArray) {
    private var index = 0

    fun readInt(): Int {
        val int = ByteBuffer.wrap(buffer, index, U32_BYTE_SIZE).int
        index += U32_BYTE_SIZE
        return int
    }

    fun readSizedString(): String {
        val length = readInt()
        val content = buffer.copyOfRange(index, index + length).decodeToString()
        index += length
        return content
    }
}
