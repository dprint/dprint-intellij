package com.dprint.services.editorservice.v5

import com.dprint.services.editorservice.FormatResult
import com.dprint.services.editorservice.process.EditorProcess
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.warnLogWithConsole
import com.intellij.openapi.project.Project
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify

class EditorServiceV5ImplTest : FunSpec({

    mockkStatic(::infoLogWithConsole)
    mockkStatic(::createNewMessage)

    val project = mockk<Project>()
    val editorProcess = mockk<EditorProcess>()
    val pendingMessages = mockk<PendingMessages>()

    val editorServiceV5 = EditorServiceV5Impl(project, editorProcess, pendingMessages)

    beforeEach {
        every { infoLogWithConsole(any(), project, any()) } returns Unit
        every { createNewMessage(any()) } answers {
            OutgoingMessage(1, firstArg())
        }
        every { pendingMessages.hasStaleMessages() } returns false
    }

    afterEach {
        clearAllMocks()
    }

    test("canFormat sends the correct message and stores a handler") {
        val testFile = "/test/File.kt"
        val onFinished = mockk<(Boolean?) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), any()) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.canFormat(testFile, onFinished)

        val expectedOutgoingMessage = OutgoingMessage(1, MessageType.CanFormat)
        expectedOutgoingMessage.addString(testFile)

        verify(exactly = 1) { pendingMessages.store(1, any()) }
        verify(exactly = 1) { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
    }

    test("canFormat's handler invokes onFinished with the result on success") {
        val testFile = "/test/File.kt"
        val onFinished = mockk<(Boolean?) -> Unit>()
        val capturedHandler = slot<(PendingMessages.Result) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), capture(capturedHandler)) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.canFormat(testFile, onFinished)
        capturedHandler.captured(PendingMessages.Result(MessageType.CanFormatResponse, true))

        verify(exactly = 1) { onFinished(true) }
    }

    test("canFormat's handler invokes onFinished with null on error") {
        val testFile = "/test/File.kt"
        val onFinished = mockk<(Boolean?) -> Unit>()
        val capturedHandler = slot<(PendingMessages.Result) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), capture(capturedHandler)) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.canFormat(testFile, onFinished)
        capturedHandler.captured(PendingMessages.Result(MessageType.ErrorResponse, "error"))

        verify(exactly = 1) { onFinished(null) }
    }

    test("fmt sends the correct message and stores a handler") {
        val testFile = "/test/File.kt"
        val testContent = "val test = \"test\""
        val onFinished = mockk<(FormatResult) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), any()) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.fmt(1, testFile, testContent, null, null, onFinished)

        val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
        // path
        expectedOutgoingMessage.addString(testFile)
        // start position
        expectedOutgoingMessage.addInt(0)
        // content length
        expectedOutgoingMessage.addInt(testContent.toByteArray().size)
        // don't override config
        expectedOutgoingMessage.addInt(0)
        // content
        expectedOutgoingMessage.addString(testContent)

        verify(exactly = 1) { pendingMessages.store(1, any()) }
        verify(exactly = 1) { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
    }

    test("fmt's handler invokes onFinished with the new content on success") {
        val testFile = "/test/File.kt"
        val testContent = "val test =   \"test\""
        val formattedContent = "val test = \"test\""
        val onFinished = mockk<(FormatResult) -> Unit>()
        val capturedHandler = slot<(PendingMessages.Result) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), capture(capturedHandler)) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.fmt(1, testFile, testContent, null, null, onFinished)
        capturedHandler.captured(PendingMessages.Result(MessageType.FormatFileResponse, formattedContent))

        verify(exactly = 1) { onFinished(FormatResult(formattedContent = formattedContent)) }
    }

    test("fmt's handler invokes onFinished with the error on failure") {
        val testFile = "/test/File.kt"
        val testContent = "val test =   \"test\""
        val testError = "test error"
        val onFinished = mockk<(FormatResult) -> Unit>()
        val capturedHandler = slot<(PendingMessages.Result) -> Unit>()

        mockkStatic("com.dprint.utils.LogUtilsKt")

        every { infoLogWithConsole(any(), project, any()) } returns Unit
        every { warnLogWithConsole(any(), project, any()) } returns Unit
        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), capture(capturedHandler)) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.fmt(1, testFile, testContent, null, null, onFinished)
        capturedHandler.captured(PendingMessages.Result(MessageType.ErrorResponse, testError))

        verify(exactly = 1) { onFinished(FormatResult(error = testError)) }
    }

    test("Cancel format creates the correct message") {
        val testId = 7

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.take(any()) } returns null

        editorServiceV5.cancelFormat(testId)

        val expectedOutgoingMessage = OutgoingMessage(1, MessageType.CancelFormat)
        expectedOutgoingMessage.addInt(testId)

        verify { pendingMessages.take(testId) }
        verify { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
    }

    test("fmt converts string indices to byte indices correctly for ASCII text") {
        val testFile = "/test/File.kt"
        val testContent = "hello world"
        val onFinished = mockk<(FormatResult) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), any()) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.fmt(1, testFile, testContent, 6, 11, onFinished)

        val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
        expectedOutgoingMessage.addString(testFile)
        expectedOutgoingMessage.addInt(6) // "hello " = 6 bytes
        expectedOutgoingMessage.addInt(11) // "hello world" = 11 bytes
        expectedOutgoingMessage.addInt(0)
        expectedOutgoingMessage.addString(testContent)

        verify(exactly = 1) { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
    }

    test("fmt converts string indices to byte indices correctly for Unicode text") {
        val testFile = "/test/File.kt"
        val testContent = "🚀 rocket"
        val onFinished = mockk<(FormatResult) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), any()) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.fmt(1, testFile, testContent, 2, 8, onFinished)

        val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
        expectedOutgoingMessage.addString(testFile)
        expectedOutgoingMessage.addInt(4) // 🚀 is 2 bytes then another 2 for ' r'
        expectedOutgoingMessage.addInt(10)
        expectedOutgoingMessage.addInt(0)
        expectedOutgoingMessage.addString(testContent)

        verify(exactly = 1) { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
    }

    test("fmt handles edge case with start index 0") {
        val testFile = "/test/File.kt"
        val testContent = "test"
        val onFinished = mockk<(FormatResult) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), any()) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.fmt(1, testFile, testContent, 0, 2, onFinished)

        val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
        expectedOutgoingMessage.addString(testFile)
        expectedOutgoingMessage.addInt(0) // start at 0
        expectedOutgoingMessage.addInt(2) // "te" = 2 bytes
        expectedOutgoingMessage.addInt(0)
        expectedOutgoingMessage.addString(testContent)

        verify(exactly = 1) { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
    }

    test("fmt handles edge case with index beyond content length") {
        val testFile = "/test/File.kt"
        val testContent = "test"
        val onFinished = mockk<(FormatResult) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), any()) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.fmt(1, testFile, testContent, 10, 20, onFinished)

        val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
        expectedOutgoingMessage.addString(testFile)
        expectedOutgoingMessage.addInt(4) // beyond length returns full content byte size
        expectedOutgoingMessage.addInt(4) // beyond length returns full content byte size
        expectedOutgoingMessage.addInt(0)
        expectedOutgoingMessage.addString(testContent)

        verify(exactly = 1) { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
    }

    test("fmt handles mixed Unicode characters correctly") {
        val testFile = "/test/File.kt"
        val testContent = "café 🎉 test"
        val onFinished = mockk<(FormatResult) -> Unit>()

        every { editorProcess.writeBuffer(any()) } returns Unit
        every { pendingMessages.store(any(), any()) } returns Unit
        every { onFinished(any()) } returns Unit

        editorServiceV5.fmt(1, testFile, testContent, 5, 7, onFinished)

        val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
        expectedOutgoingMessage.addString(testFile)
        expectedOutgoingMessage.addInt(testContent.substring(0, 5).encodeToByteArray().size) // Dynamic calculation
        expectedOutgoingMessage.addInt(testContent.substring(0, 7).encodeToByteArray().size) // Dynamic calculation
        expectedOutgoingMessage.addInt(0)
        expectedOutgoingMessage.addString(testContent)

        verify(exactly = 1) { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
    }
})
