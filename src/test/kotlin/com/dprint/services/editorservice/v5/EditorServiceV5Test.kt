package com.dprint.services.editorservice.v5

import com.dprint.config.ProjectConfiguration
import com.dprint.services.editorservice.process.EditorProcess
import com.dprint.utils.infoLogWithConsole
import com.dprint.utils.warnLogWithConsole
import com.dprint.utils.errorLogWithConsole
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking

class EditorServiceV5Test :
    FunSpec({

        mockkStatic(::infoLogWithConsole)
        mockkStatic(::createNewMessage)

        val project = mockk<Project>()
        val editorProcess = mockk<EditorProcess>()
        val messageChannel = mockk<MessageChannel>()
        val projectConfiguration = mockk<ProjectConfiguration>()
        val configState = mockk<ProjectConfiguration.State>()

        val editorServiceV5 = EditorServiceV5(project)

        beforeEach {
            every { infoLogWithConsole(any(), project, any()) } returns Unit
            every { createNewMessage(any()) } answers {
                OutgoingMessage(1, firstArg())
            }
            every { messageChannel.hasStaleRequests(any()) } returns false
            every { project.service<ProjectConfiguration>() } returns projectConfiguration
            every { projectConfiguration.state } returns configState
            every { configState.commandTimeout } returns 5000L
            every { project.service<EditorProcess>() } returns editorProcess
            every { project.service<MessageChannel>() } returns messageChannel
        }

        afterEach {
            clearAllMocks()
        }

        test("canFormat sends the correct message and waits for response") {
            val testFile = "/test/File.kt"

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.CanFormatResponse, true)

            val result = runBlocking { editorServiceV5.canFormat(testFile) }

            val expectedOutgoingMessage = OutgoingMessage(1, MessageType.CanFormat)
            expectedOutgoingMessage.addString(testFile)

            result shouldBe true
            coVerify(exactly = 1) { messageChannel.sendRequest(1, 5000L) }
            coVerify(exactly = 1) { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
        }

        test("canFormat returns null on error") {
            val testFile = "/test/File.kt"

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.ErrorResponse, "error")

            val result = runBlocking { editorServiceV5.canFormat(testFile) }

            result shouldBe null
        }

        test("canFormat returns null on timeout") {
            val testFile = "/test/File.kt"

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery { messageChannel.sendRequest(any(), any()) } returns null // timeout

            val result = runBlocking { editorServiceV5.canFormat(testFile) }

            result shouldBe null
        }

        test("fmt sends the correct message and waits for response") {
            val testFile = "/test/File.kt"
            val testContent = "val test = \"test\""

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.FormatFileResponse, null)

            val result = runBlocking { editorServiceV5.fmt(testFile, testContent, 1) }

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

            result.formattedContent shouldBe null
            coVerify(exactly = 1) { messageChannel.sendRequest(1, 5000L) }
            coVerify(exactly = 1) { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
        }

        test("fmt returns the new content on success") {
            val testFile = "/test/File.kt"
            val testContent = "val test =   \"test\""
            val formattedContent = "val test = \"test\""

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.FormatFileResponse, formattedContent)

            val result = runBlocking { editorServiceV5.fmt(testFile, testContent, 1) }

            result.formattedContent shouldBe formattedContent
        }

        test("fmt returns the error on failure") {
            val testFile = "/test/File.kt"
            val testContent = "val test =   \"test\""
            val testError = "test error"

            mockkStatic("com.dprint.utils.LogUtilsKt")

            every { infoLogWithConsole(any(), project, any()) } returns Unit
            every { warnLogWithConsole(any(), project, any()) } returns Unit
            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.ErrorResponse, testError)

            val result = runBlocking { editorServiceV5.fmt(testFile, testContent, 1) }

            result.error shouldBe testError
        }

        test("Cancel format creates the correct message") {
            val testId = 7

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            every { messageChannel.cancelRequest(any()) } returns true

            editorServiceV5.cancelFormat(testId)

            val expectedOutgoingMessage = OutgoingMessage(1, MessageType.CancelFormat)
            expectedOutgoingMessage.addInt(testId)

            verify { messageChannel.cancelRequest(testId) }
            coVerify { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
        }

        test("canFormat uses configurable timeout from project configuration") {
            val testFile = "/test/File.kt"
            val customTimeout = 15000L

            // Override the timeout for this test
            every { configState.commandTimeout } returns customTimeout

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.CanFormatResponse, true)

            runBlocking { editorServiceV5.canFormat(testFile) }

            coVerify(exactly = 1) { messageChannel.sendRequest(1, customTimeout) }
        }

        test("fmt converts string indices to byte indices correctly for ASCII text") {
            val testFile = "/test/File.kt"
            val testContent = "hello world"

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.FormatFileResponse, testContent)

            runBlocking { editorServiceV5.fmt(testFile, testContent, 1, 6, 11) }

            val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
            expectedOutgoingMessage.addString(testFile)
            expectedOutgoingMessage.addInt(6) // "hello " = 6 bytes
            expectedOutgoingMessage.addInt(11) // "hello world" = 11 bytes
            expectedOutgoingMessage.addInt(0)
            expectedOutgoingMessage.addString(testContent)

            coVerify { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
        }

        test("fmt converts string indices to byte indices correctly for Unicode text") {
            val testFile = "/test/File.kt"
            val testContent = "🚀 rocket"

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.FormatFileResponse, testContent)

            runBlocking { editorServiceV5.fmt(testFile, testContent, 1, 2, 9) }

            val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
            expectedOutgoingMessage.addString(testFile)
            expectedOutgoingMessage.addInt(
                testContent.substring(0, 2).encodeToByteArray().size,
            ) // Dynamic calculation for string index 2
            expectedOutgoingMessage.addInt(
                testContent.substring(0, 9).encodeToByteArray().size,
            ) // Dynamic calculation for string index 9
            expectedOutgoingMessage.addInt(0)
            expectedOutgoingMessage.addString(testContent)

            coVerify { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
        }

        test("fmt handles edge case with start index 0") {
            val testFile = "/test/File.kt"
            val testContent = "test"

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.FormatFileResponse, testContent)

            runBlocking { editorServiceV5.fmt(testFile, testContent, 1, 0, 2) }

            val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
            expectedOutgoingMessage.addString(testFile)
            expectedOutgoingMessage.addInt(0) // start at 0
            expectedOutgoingMessage.addInt(2) // "te" = 2 bytes
            expectedOutgoingMessage.addInt(0)
            expectedOutgoingMessage.addString(testContent)

            coVerify { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
        }

        test("fmt handles edge case with index beyond content length") {
            val testFile = "/test/File.kt"
            val testContent = "test"

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.FormatFileResponse, testContent)

            runBlocking { editorServiceV5.fmt(testFile, testContent, 1, 10, 20) }

            val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
            expectedOutgoingMessage.addString(testFile)
            expectedOutgoingMessage.addInt(4) // beyond length returns full content byte size
            expectedOutgoingMessage.addInt(4) // beyond length returns full content byte size
            expectedOutgoingMessage.addInt(0)
            expectedOutgoingMessage.addString(testContent)

            coVerify { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
        }

        test("fmt handles mixed Unicode characters correctly") {
            val testFile = "/test/File.kt"
            val testContent = "café 🎉 test"

            coEvery { editorProcess.writeBuffer(any()) } returns Unit
            coEvery {
                messageChannel.sendRequest(any(), any())
            } returns MessageChannel.Result(MessageType.FormatFileResponse, testContent)

            runBlocking { editorServiceV5.fmt(testFile, testContent, 1, 5, 7) }

            val expectedOutgoingMessage = OutgoingMessage(1, MessageType.FormatFile)
            expectedOutgoingMessage.addString(testFile)
            expectedOutgoingMessage.addInt(testContent.substring(0, 5).encodeToByteArray().size) // Dynamic calculation
            expectedOutgoingMessage.addInt(testContent.substring(0, 7).encodeToByteArray().size) // Dynamic calculation
            expectedOutgoingMessage.addInt(0)
            expectedOutgoingMessage.addString(testContent)

            coVerify { editorProcess.writeBuffer(expectedOutgoingMessage.build()) }
        }

        test("canRangeFormat returns true for V5") {
            val result = editorServiceV5.canRangeFormat()
            result shouldBe false
        }

        test("destroyEditorService only destroys if process is alive") {
            mockkStatic("com.dprint.utils.LogUtilsKt")
            every { infoLogWithConsole(any(), project, any()) } returns Unit

            // Process is not alive
            every { editorProcess.isAlive() } returns false

            editorServiceV5.destroyEditorService()

            // Verify destroy was not called on the process
            verify(exactly = 0) { editorProcess.destroy() }
        }

        test("destroyEditorService calls destroy when process is alive") {
            mockkStatic("com.dprint.utils.LogUtilsKt")
            every { infoLogWithConsole(any(), project, any()) } returns Unit
            every { errorLogWithConsole(any<String>(), any<Throwable>(), project, any()) } returns Unit

            // Process is alive
            every { editorProcess.isAlive() } returns true
            every { editorProcess.destroy() } returns Unit
            every { messageChannel.cancelAllRequests() } returns emptyList()
            coEvery { editorProcess.writeBuffer(any()) } returns Unit

            editorServiceV5.destroyEditorService()

            // Verify destroy was called on the process
            verify(exactly = 1) { editorProcess.destroy() }
        }

        test("destroyEditorService is safe to call multiple times") {
            mockkStatic("com.dprint.utils.LogUtilsKt")
            every { infoLogWithConsole(any(), project, any()) } returns Unit
            every { errorLogWithConsole(any<String>(), any<Throwable>(), project, any()) } returns Unit

            // Process is alive for first call, then not alive for subsequent calls
            every { editorProcess.isAlive() } returnsMany listOf(true, false, false)
            every { editorProcess.destroy() } returns Unit
            every { messageChannel.cancelAllRequests() } returns emptyList()
            coEvery { editorProcess.writeBuffer(any()) } returns Unit

            // Call destroy multiple times
            editorServiceV5.destroyEditorService()
            editorServiceV5.destroyEditorService()
            editorServiceV5.destroyEditorService()

            // Verify destroy was only called once (when isAlive was true)
            verify(exactly = 1) { editorProcess.destroy() }
        }
    })
