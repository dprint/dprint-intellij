package com.dprint.formatter

import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.services.editorservice.FormatResult
import com.dprint.utils.infoLogWithConsole
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify

class DprintFormattingTaskTest : FunSpec({
    val path = "/some/path"

    mockkStatic(::infoLogWithConsole)

    val project = mockk<Project>()
    val editorServiceManager = mockk<EditorServiceManager>()
    val formattingRequest = mockk<AsyncFormattingRequest>(relaxed = true)
    lateinit var dprintFormattingTask: DprintFormattingTask

    beforeEach {
        every { infoLogWithConsole(any(), project, any()) } returns Unit
        every { editorServiceManager.maybeGetFormatId() } returns 1

        dprintFormattingTask = DprintFormattingTask(project, editorServiceManager, formattingRequest, path)
    }

    afterEach { clearAllMocks() }

    test("it calls editorServiceManager.format correctly when range formatting is disabled") {
        val testContent = "val test =   \"test\""
        val successContent = "val test = \"test\""
        val formatResult = FormatResult()
        val onFinished = slot<(FormatResult) -> Unit>()

        every { formattingRequest.documentText } returns testContent
        every { formattingRequest.formattingRanges } returns mutableListOf(TextRange(0, testContent.length))
        every { editorServiceManager.canRangeFormat() } returns false
        // range indexes should be null as range format is disabled
        every {
            editorServiceManager.format(
                any(), path, testContent, 0, testContent.length, capture(onFinished),
            )
        } answers {
            formatResult.formattedContent = successContent
            onFinished.captured.invoke(formatResult)
        }

        dprintFormattingTask.run()

        verify(exactly = 1) { editorServiceManager.format(any(), path, testContent, 0, testContent.length, any()) }
        verify { formattingRequest.onTextReady(successContent) }
    }

    test("it calls editorServiceManager.format correctly when range formatting has a single range") {
        val testContent = "val test =   \"test\""
        val successContent = "val test = \"test\""
        val formatResult = FormatResult()
        val onFinished = slot<(FormatResult) -> Unit>()

        every { formattingRequest.documentText } returns testContent
        every { formattingRequest.formattingRanges } returns mutableListOf(TextRange(0, testContent.length))
        every { editorServiceManager.canRangeFormat() } returns true
        // range indexes should be null as range format is disabled
        every {
            editorServiceManager.format(
                any(), path, testContent, 0, testContent.length, capture(onFinished),
            )
        } answers {
            formatResult.formattedContent = successContent
            onFinished.captured.invoke(formatResult)
        }

        dprintFormattingTask.run()

        verify(exactly = 1) { editorServiceManager.format(any(), path, testContent, 0, testContent.length, any()) }
        verify { formattingRequest.onTextReady(successContent) }
    }

    test("it calls editorServiceManager.format correctly when range formatting has multiple ranges") {
        val testContentPart1 = "val    test"
        val testContentPart2 = " =   \"test\""
        val testContent = testContentPart1 + testContentPart2

        val successContentPart1 = "val test =   \"test\""
        val successContentPart2 = "val test = \"test\""

        val formatResult1 = FormatResult()
        formatResult1.formattedContent = successContentPart1
        val onFinished1 = slot<(FormatResult) -> Unit>()

        val formatResult2 = FormatResult()
        val onFinished2 = slot<(FormatResult) -> Unit>()

        every { formattingRequest.documentText } returns testContent
        every {
            formattingRequest.formattingRanges
        } returns
            mutableListOf(
                TextRange(0, testContentPart1.length),
                TextRange(testContentPart1.length, testContent.length),
            )
        every { editorServiceManager.canRangeFormat() } returns true
        // range indexes should be null as range format is disabled
        every {
            editorServiceManager.format(
                1, path, testContent, any(), any(), capture(onFinished1),
            )
        } answers {
            onFinished1.captured.invoke(formatResult1)
        }

        every {
            editorServiceManager.format(
                1, path, successContentPart1, any(), any(), capture(onFinished2),
            )
        } answers {
            formatResult2.formattedContent = successContentPart2
            onFinished2.captured.invoke(formatResult2)
        }

        dprintFormattingTask.run()

        verify(exactly = 1) { editorServiceManager.format(1, path, testContent, 0, testContentPart1.length, any()) }
        verify(exactly = 1) { editorServiceManager.format(1, path, successContentPart1, 8, successContentPart1.length, any()) }
        verify { formattingRequest.onTextReady(successContentPart2) }
    }
})
