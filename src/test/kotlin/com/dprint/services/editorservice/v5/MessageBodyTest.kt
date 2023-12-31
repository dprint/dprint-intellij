package com.dprint.services.editorservice.v5

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer

class MessageBodyTest : FunSpec({
    test("It decodes an int") {
        val int = 7
        val buffer = ByteBuffer.allocate(4)
        buffer.putInt(7)
        val messageBody = MessageBody(buffer.array())
        messageBody.readInt() shouldBe int
    }

    test("It decodes a string") {
        val text = "blah!"
        val textAsByteArray = text.encodeToByteArray()
        val sizeBuffer = ByteBuffer.allocate(4)
        sizeBuffer.putInt(textAsByteArray.size)
        val buffer = ByteBuffer.allocate(4 + textAsByteArray.size)
        // Need to call array here so the 0's get copied into the new buffer
        buffer.put(sizeBuffer.array())
        buffer.put(textAsByteArray)
        val messageBody = MessageBody(buffer.array())
        messageBody.readSizedString() shouldBe text
    }
})
