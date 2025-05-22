package com.dprint.services

import com.dprint.messages.DprintAction
import com.dprint.messages.DprintAction.Companion.DPRINT_ACTION_TOPIC
import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.utils.isFormattableFile
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.jupiter.api.assertThrows

class FormatterServiceImplTest : FunSpec({
    val testPath = "/test/path"
    val testText = "val test = \"test\""

    mockkStatic(::isFormattableFile)

    val virtualFile = mockk<VirtualFile>()
    val document = mockk<Document>()
    val project = mockk<Project>()
    val dprintAction = mockk<DprintAction>(relaxed = true)
    val editorServiceManager = mockk<EditorServiceManager>(relaxed = true)

    val formatterService = FormatterServiceImpl(project, editorServiceManager)

    beforeEach {
        every { virtualFile.path } returns testPath
        every { document.text } returns testText
        every { project.messageBus.syncPublisher(DPRINT_ACTION_TOPIC) } returns dprintAction
    }

    afterEach {
        clearAllMocks()
    }

    test("It doesn't format if cached can format result is false") {
        every { isFormattableFile(project, virtualFile) } returns true
        every { editorServiceManager.canFormatCached(testPath) } returns false

        formatterService.format(virtualFile, document)

        verify(exactly = 1) { dprintAction.formattingSkipped(testPath) }
        verify(exactly = 0) { dprintAction.formattingStarted(any()) }
        verify(exactly = 0) { editorServiceManager.format(testPath, testPath, any()) }
    }

    test("It doesn't format if cached can format result is null") {
        every { isFormattableFile(project, virtualFile) } returns true
        every { editorServiceManager.canFormatCached(testPath) } returns null

        formatterService.format(virtualFile, document)

        verify(exactly = 1) { dprintAction.formattingSkipped(testPath) }
        verify(exactly = 0) { dprintAction.formattingStarted(any()) }
        verify(exactly = 0) { editorServiceManager.format(testPath, testPath, any()) }
    }

    test("It formats if cached can format result is true") {
        every { isFormattableFile(project, virtualFile) } returns true
        every { editorServiceManager.canFormatCached(testPath) } returns true

        formatterService.format(virtualFile, document)

        verify(exactly = 1) { dprintAction.formattingStarted(testPath) }
        verify(exactly = 1) { editorServiceManager.format(testPath, testText, any()) }
        verify(exactly = 1) { dprintAction.formattingSucceeded(testPath, any()) }
    }

    test("It records failures and propagates exceptions") {
        every { isFormattableFile(project, virtualFile) } returns true
        every { editorServiceManager.canFormatCached(testPath) } returns true
        every { editorServiceManager.format(testPath, testText, any()) } throws IllegalStateException("boom!")

        assertThrows<IllegalStateException> {
            formatterService.format(virtualFile, document)
        }

        verify(exactly = 1) { dprintAction.formattingStarted(testPath) }
        verify(exactly = 1) { editorServiceManager.format(testPath, testText, any()) }
        verify(exactly = 1) { dprintAction.formattingFailed(testPath, any(), "boom!") }
    }
})
