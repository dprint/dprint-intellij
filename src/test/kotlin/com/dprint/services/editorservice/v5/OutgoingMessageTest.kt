package com.dprint.services.editorservice.v5

import com.intellij.util.io.toByteArray
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer

val SUCCESS_MESSAGE = byteArrayOf(-1, -1, -1, -1)

internal class OutgoingMessageTest :
    FunSpec({
        test("It builds a string message") {
            val id = 1
            val type = MessageType.Active
            val text = "blah!"
            val textAsBytes = text.toByteArray()
            val outgoingMessage = OutgoingMessage(id, type)
            outgoingMessage.addString(text)

            // 4 is for the size of the part, it has a single part
            val bodyLength = 4 + text.length
            // id + message type + body size + part size + part content + success message
            val expectedSize = 4 * 3 + 4 + text.length + SUCCESS_MESSAGE.size
            val expected = ByteBuffer.allocate(expectedSize)
            expected.put(createIntBytes(id))
            expected.put(createIntBytes(type.intValue))
            expected.put(createIntBytes(bodyLength))
            expected.put(createIntBytes(text.length))
            expected.put(textAsBytes)
            expected.put(SUCCESS_MESSAGE)

            outgoingMessage.build() shouldBe expected.array()
        }

        test("It builds an int message") {
            val id = 1
            val type = MessageType.Active
            val int = 2
            val outgoingMessage = OutgoingMessage(id, type)
            outgoingMessage.addInt(int)

            // id + message type + body size + part content + success message
            val expectedSize = 4 * 3 + 4 + SUCCESS_MESSAGE.size
            val expected = ByteBuffer.allocate(expectedSize)
            expected.put(createIntBytes(id))
            expected.put(createIntBytes(type.intValue))
            expected.put(createIntBytes(4)) // body length
            expected.put(createIntBytes(int))
            expected.put(SUCCESS_MESSAGE)

            outgoingMessage.build() shouldBe expected.array()
        }

        test("It builds a combined message") {
            val id = 1
            val type = MessageType.Active
            val int = 2
            val text = "blah!"
            val textAsBytes = text.toByteArray()
            val outgoingMessage = OutgoingMessage(id, type)
            outgoingMessage.addInt(int)
            outgoingMessage.addString(text)

            // body length
            val bodyLength = 4 + 4 + text.length
            // id + message type + body size + int part + string part size + string part content + success message
            val expectedSize = 4 * 3 + 4 + 4 + text.length + SUCCESS_MESSAGE.size
            val expected = ByteBuffer.allocate(expectedSize)
            expected.put(createIntBytes(id))
            expected.put(createIntBytes(type.intValue))
            expected.put(createIntBytes(bodyLength))
            expected.put(createIntBytes(int))
            expected.put(createIntBytes(text.length))
            expected.put(textAsBytes)
            expected.put(SUCCESS_MESSAGE)

            outgoingMessage.build() shouldBe expected.array()
        }
    })

private fun createIntBytes(int: Int): ByteArray {
    val buffer = ByteBuffer.allocate(4)
    buffer.putInt(int)
    return buffer.toByteArray()
}
