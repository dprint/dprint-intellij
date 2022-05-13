package com.dprint.services.editorservice.v5

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals

internal class MessageBodyTest {
    @Test
    fun testItDecodesAnInt() {
        val int = 7
        val buffer = ByteBuffer.allocate(4)
        buffer.putInt(7)
        val messageBody = MessageBody(buffer.array())
        assertEquals(int, messageBody.readInt())
    }

    @Test
    fun testItDecodesAString() {
        val text = "blah!"
        val textAsByteArray = text.encodeToByteArray()
        val sizeBuffer = ByteBuffer.allocate(4)
        sizeBuffer.putInt(textAsByteArray.size)
        val buffer = ByteBuffer.allocate(4 + textAsByteArray.size)
        // Need to call array here so the 0's get copied into the new buffer
        buffer.put(sizeBuffer.array())
        buffer.put(textAsByteArray)
        val messageBody = MessageBody(buffer.array())
        assertEquals(text, messageBody.readSizedString())
    }
}
