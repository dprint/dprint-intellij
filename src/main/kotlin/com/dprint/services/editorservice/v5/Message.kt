package com.dprint.services.editorservice.v5

import com.dprint.services.editorservice.exceptions.UnsupportedMessagePartException
import java.nio.ByteBuffer

// Dprint uses unsigned bytes of 4x255 for the success message and that translates
// to 4x-1 in the jvm's signed bytes.
val SUCCESS_MESSAGE = byteArrayOf(-1, -1, -1, -1)
private const val U32_BYTE_SIZE = 4

class Message(val id: Int, val type: MessageType) {
    private var parts = mutableListOf<Any>()

    fun addString(str: String) {
        parts.add(str.encodeToByteArray())
    }

    fun addInt(int: Int) {
        parts.add(int)
    }

    private fun intToFourByteArray(int: Int): ByteArray {
        val buffer = ByteBuffer.allocate(U32_BYTE_SIZE)
        buffer.putInt(int)
        return buffer.array()
    }

    fun build(): ByteArray {
        var bodyLength = 0
        for (part in parts) {
            when (part) {
                is Int -> bodyLength += U32_BYTE_SIZE
                is ByteArray -> bodyLength += (part.size + U32_BYTE_SIZE)
            }
        }
        val byteLength = bodyLength + U32_BYTE_SIZE * U32_BYTE_SIZE
        val buffer = ByteBuffer.allocate(byteLength)

        buffer.put(intToFourByteArray(id))
        buffer.put(intToFourByteArray(type.intValue))
        buffer.put(intToFourByteArray(bodyLength))

        for (part in parts) {
            when (part) {
                is ByteArray -> {
                    buffer.put(intToFourByteArray(part.size))
                    buffer.put(part)
                }
                is Int -> {
                    buffer.put(intToFourByteArray(part))
                }
                else -> {
                    throw UnsupportedMessagePartException(
                        "${part.javaClass.name} is not a supported message part type."
                    )
                }
            }
        }

        buffer.put(SUCCESS_MESSAGE)

        if (buffer.hasRemaining()) {
            val message = "Incorrect message size, expected $byteLength and got ${byteLength - buffer.remaining()}"
            throw UnsupportedMessagePartException(message)
        }
        return buffer.array()
    }
}
