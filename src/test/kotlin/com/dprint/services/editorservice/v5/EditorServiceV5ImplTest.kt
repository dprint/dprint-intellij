package com.dprint.services.editorservice.v5

import com.dprint.services.editorservice.EditorProcess
import com.dprint.services.editorservice.FormatResult
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
            Message(1, firstArg())
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

        val expectedMessage = Message(1, MessageType.CanFormat)
        expectedMessage.addString(testFile)

        verify(exactly = 1) { pendingMessages.store(1, any()) }
        verify(exactly = 1) { editorProcess.writeBuffer(expectedMessage.build()) }
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

        val expectedMessage = Message(1, MessageType.FormatFile)
        // path
        expectedMessage.addString(testFile)
        // start position
        expectedMessage.addInt(0)
        // content length
        expectedMessage.addInt(testContent.toByteArray().size)
        // don't override config
        expectedMessage.addInt(0)
        // content
        expectedMessage.addString(testContent)

        verify(exactly = 1) { pendingMessages.store(1, any()) }
        verify(exactly = 1) { editorProcess.writeBuffer(expectedMessage.build()) }
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

        val expectedMessage = Message(1, MessageType.CancelFormat)
        expectedMessage.addInt(testId)

        verify { pendingMessages.take(testId) }
        verify { editorProcess.writeBuffer(expectedMessage.build()) }
    }
})
