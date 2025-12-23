package com.dprint.services.editorservice.v5

import com.dprint.i18n.DprintBundle
import com.dprint.services.editorservice.exceptions.UnsupportedMessagePartException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

private var messageId = AtomicInteger(0)

fun createNewMessage(type: MessageType): OutgoingMessage = OutgoingMessage(getNextMessageId(), type)

fun getNextMessageId(): Int = messageId.incrementAndGet()

class OutgoingMessage(
    val id: Int,
    private val type: MessageType,
) {
    // Dprint uses unsigned bytes of 4x255 for the success message and that translates
    // to 4x-1 in the jvm's signed bytes.
    private val successMessage = byteArrayOf(-1, -1, -1, -1)
    private val u32ByteSize = 4
    private var parts = mutableListOf<Any>()

    fun addString(str: String) {
        parts.add(str.encodeToByteArray())
    }

    fun addInt(int: Int) {
        parts.add(int)
    }

    private fun intToFourByteArray(int: Int): ByteArray {
        val buffer = ByteBuffer.allocate(u32ByteSize)
        buffer.putInt(int)
        return buffer.array()
    }

    fun build(): ByteArray {
        var bodyLength = 0
        for (part in parts) {
            when (part) {
                is Int -> bodyLength += u32ByteSize
                is ByteArray -> bodyLength += (part.size + u32ByteSize)
            }
        }
        val byteLength = bodyLength + u32ByteSize * u32ByteSize
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
                        DprintBundle.message("editor.service.unsupported.message.type", part::class.java.simpleName),
                    )
                }
            }
        }

        buffer.put(successMessage)

        if (buffer.hasRemaining()) {
            val message =
                DprintBundle.message(
                    "editor.service.incorrect.message.size",
                    byteLength,
                    byteLength - buffer.remaining(),
                )
            throw UnsupportedMessagePartException(message)
        }
        return buffer.array()
    }
}
