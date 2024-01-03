package com.dprint.formatter

import com.dprint.services.editorservice.EditorServiceManager
import com.dprint.services.editorservice.FormatResult
import com.dprint.utils.errorLogWithConsole
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

class DprintFormattingTaskTest : FunSpec({
    val path = "/some/path"

    mockkStatic(::infoLogWithConsole)

    val project = mockk<Project>()
    val editorServiceManager = mockk<EditorServiceManager>()
    val formattingRequest = mockk<AsyncFormattingRequest>(relaxed = true)
    lateinit var dprintFormattingTask: DprintFormattingTask

    beforeEach {
        every { infoLogWithConsole(any(), project, any()) } returns Unit
        every { editorServiceManager.maybeGetFormatId() } returnsMany mutableListOf(1, 2, 3)

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
                1, path, testContent, 0, testContent.length, capture(onFinished),
            )
        } answers {
            formatResult.formattedContent = successContent
            onFinished.captured.invoke(formatResult)
        }

        dprintFormattingTask.run()

        verify(exactly = 1) { editorServiceManager.format(1, path, testContent, 0, testContent.length, any()) }
        verify { formattingRequest.onTextReady(successContent) }
    }

    test("it calls editorServiceManager.format correctly when range formatting has a single range") {
        val testContent = "val test =   \"test\""
        val successContent = "val test = \"test\""
        val formatResult = FormatResult()
        val onFinished = slot<(FormatResult) -> Unit>()

        every { formattingRequest.documentText } returns testContent
        every { formattingRequest.formattingRanges } returns mutableListOf(TextRange(0, testContent.length - 1))
        every { editorServiceManager.canRangeFormat() } returns true
        // range indexes should be null as range format is disabled
        every {
            editorServiceManager.format(
                1, path, testContent, 0, testContent.length - 1, capture(onFinished),
            )
        } answers {
            formatResult.formattedContent = successContent
            onFinished.captured.invoke(formatResult)
        }

        dprintFormattingTask.run()

        verify(exactly = 1) { editorServiceManager.format(1, path, testContent, 0, testContent.length - 1, any()) }
        verify { formattingRequest.onTextReady(successContent) }
    }

    test("it calls editorServiceManager.format correctly when range formatting has multiple ranges") {
        val unformattedPart1 = "val    test"
        val unformattedPart2 = " =   \"test\""
        val testContent = unformattedPart1 + unformattedPart2

        val formattedPart1 = "val test"
        val formattedPart2 = " = \"test\""
        val successContentPart1 = formattedPart1 + unformattedPart2
        val successContentPart2 = formattedPart1 + formattedPart2

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
                TextRange(0, unformattedPart1.length),
                TextRange(unformattedPart1.length, unformattedPart1.length + unformattedPart2.length),
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
                2, path, successContentPart1, any(), any(), capture(onFinished2),
            )
        } answers {
            formatResult2.formattedContent = successContentPart2
            onFinished2.captured.invoke(formatResult2)
        }

        dprintFormattingTask.run()

        // Verify the correct range lengths are recalculated
        verify(exactly = 1) { editorServiceManager.format(1, path, testContent, 0, unformattedPart1.length, any()) }
        verify(
            exactly = 1,
        ) { editorServiceManager.format(2, path, successContentPart1, formattedPart1.length, formattedPart1.length + unformattedPart2.length, any()) }
        verify { formattingRequest.onTextReady(successContentPart2) }
    }

    test("it calls editorServiceManager.cancel with the format id when cancelled") {
        val testContent = "val test =   \"test\""
        val formattedContent = "val test = \"test\""
        val formatResult = FormatResult()
        val onFinished = slot<(FormatResult) -> Unit>()

        mockkStatic("com.dprint.utils.LogUtilsKt")
        every { infoLogWithConsole(any(), project, any()) } returns Unit
        every { errorLogWithConsole(any(), any(), project, any()) } returns Unit
        every { formattingRequest.documentText } returns testContent
        every { formattingRequest.formattingRanges } returns mutableListOf(TextRange(0, testContent.length))
        every { editorServiceManager.canRangeFormat() } returns false
        every { editorServiceManager.canCancelFormat() } returns true
        every { editorServiceManager.cancelFormat(1) } returns Unit
        // range indexes should be null as range format is disabled
        every {
            editorServiceManager.format(
                any(), path, testContent, 0, testContent.length, capture(onFinished),
            )
        } answers {
            CompletableFuture.runAsync {
                dprintFormattingTask.cancel()
                Thread.sleep(5000)
                formatResult.formattedContent = formattedContent
                onFinished.captured.invoke(formatResult)
            }
        }

        dprintFormattingTask.run()

        verify(exactly = 1) { editorServiceManager.format(1, path, testContent, 0, testContent.length, any()) }
        verify(exactly = 1) { editorServiceManager.cancelFormat(1) }
        verify(exactly = 1) { errorLogWithConsole(any(), any(CancellationException::class), project, any()) }
        verify(exactly = 0) { formattingRequest.onTextReady(any()) }
    }

    test("it calls formattingRequest.onError when the format returns a failure state") {
        val testContent = "val test =   \"test\""
        val testFailure = "Test failure"
        val formatResult = FormatResult()
        val onFinished = slot<(FormatResult) -> Unit>()

        every { formattingRequest.documentText } returns testContent
        every { formattingRequest.formattingRanges } returns mutableListOf(TextRange(0, testContent.length))
        every { editorServiceManager.canRangeFormat() } returns false
        // range indexes should be null as range format is disabled
        every {
            editorServiceManager.format(
                1, path, testContent, 0, testContent.length, capture(onFinished),
            )
        } answers {
            formatResult.error = testFailure
            onFinished.captured.invoke(formatResult)
        }

        dprintFormattingTask.run()

        verify(exactly = 1) { editorServiceManager.format(1, path, testContent, 0, testContent.length, any()) }
        verify { formattingRequest.onError(any(), testFailure) }
    }
})
